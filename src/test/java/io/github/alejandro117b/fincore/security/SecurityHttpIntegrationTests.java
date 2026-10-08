package io.github.alejandro117b.fincore.security;

import java.net.URI;
import java.net.http.*;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.security.interfaces.RSAPrivateKey;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.github.alejandro117b.fincore.auth.*;
import io.github.alejandro117b.fincore.account.*;
import io.github.alejandro117b.fincore.customer.CustomerService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"fincore.demo.seed.enabled=false", "springdoc.api-docs.enabled=true", "springdoc.swagger-ui.enabled=true"})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class SecurityHttpIntegrationTests {
    private static final String SCHEMA = "security_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String PASSWORD = "security-test-password";
    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }
    @LocalServerPort private int port;
    @Autowired private AuthUserProvisioningService provisioning;
    @Autowired private AuthUserRepository users;
    @Autowired private JwtTokenService tokens;
    @Autowired private JwtEncoder encoder;
    @Autowired private JwtDecoder decoder;
    @Autowired private CustomerService customers;
    @Autowired private AccountService accounts;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;
    @Autowired private DataSource dataSource;
    @Autowired private Clock clock;
    @Autowired private JsonMapper json;
    @Autowired private PasswordEncoder passwords;
    private final HttpClient client = HttpClient.newHttpClient();
    private Fixture owner;
    private Fixture other;
    private Fixture admin;
    private Fixture both;
    private record Fixture(UUID userId, UUID customerId, UUID accountId, String email, String token) {
        @Override public String toString() { return "Fixture[userId=" + userId + "]"; }
    }

    @BeforeAll
    void provisionFixtures() {
        owner = fixture(Set.of(AuthRole.USER));
        other = fixture(Set.of(AuthRole.USER));
        admin = fixture(Set.of(AuthRole.ADMIN));
        both = fixture(Set.of(AuthRole.USER, AuthRole.ADMIN));
    }

    private Fixture fixture(Set<AuthRole> roles) {
        UUID customerId = roles.contains(AuthRole.USER) ? customers.create("Security", "Fixture", "contact@example.test").id() : null;
        UUID accountId = customerId == null ? null : accounts.create(customerId, AccountType.CHECKING, "MXN").id();
        String email = UUID.randomUUID() + "@security.example.test";
        UUID id = provisioning.provision(email, PASSWORD, customerId, roles);
        return new Fixture(id, customerId, accountId, email, tokens.issue(users.findById(id).orElseThrow()));
    }

    private HttpResponse<String> request(String method, String path, String body, String token) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(java.time.Duration.ofSeconds(15)).header("Accept", "application/json")
                .header("X-Request-Id", "untrusted-client-value");
        if (token != null) { builder.header("Authorization", "Bearer " + token); }
        if (body != null) { builder.header("Content-Type", "application/json"); }
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.path("code").asString()).isEqualTo(code);
        String id = response.headers().firstValue("X-Request-Id").orElseThrow();
        assertThat(id).matches("[0-9a-f-]{36}").isNotEqualTo("untrusted-client-value");
        assertThat(body.path("requestId").asString()).isEqualTo(id);
        assertThat(body.path("timestamp").asString()).isNotBlank();
        assertThat(body.path("status").asInt()).isEqualTo(status);
        assertThat(body.path("details").isArray()).isTrue();
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain(PASSWORD, "Authorization", "stackTrace", "passwordHash");
        if (status == 401 && !code.equals("INVALID_CREDENTIALS")) {
            assertThat(response.headers().firstValue("WWW-Authenticate")).isPresent();
        }
        return body;
    }

    @Test
    void publicLoginNormalizesEmailAndReturnsOnlyTheDocumentedTokenContract() throws Exception {
        var response = request("POST", "/api/v1/auth/login",
                json.writeValueAsString(java.util.Map.of("email", " " + owner.email().toUpperCase(java.util.Locale.ROOT) + " ", "password", PASSWORD)), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("tokenType").asString()).isEqualTo("Bearer");
        assertThat(body.path("expiresIn").asLong()).isEqualTo(900);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        Jwt jwt = decoder.decode(body.path("accessToken").asString());
        assertThat(jwt.getHeaders().get("alg")).isEqualTo("RS256");
        assertThat(jwt.getClaims().keySet()).containsExactlyInAnyOrder("sub", "iss", "aud", "iat", "nbf", "exp", "jti", "ver");
        assertThat(jwt.getSubject()).isEqualTo(owner.userId().toString());
        assertThat(java.time.Duration.between(jwt.getIssuedAt(), jwt.getExpiresAt()).getSeconds()).isEqualTo(900);
        assertThat(response.headers().allValues("Set-Cookie")).isEmpty();
        assertThat(response.body()).doesNotContain(PASSWORD, "passwordHash", owner.email());
    }

    @Test
    void unknownEmailWrongPasswordAndDisabledUserHaveTheSameLoginError() throws Exception {
        var disabled = fixture(Set.of(AuthRole.USER));
        var user = users.findById(disabled.userId()).orElseThrow();
        user.disable(clock.instant());
        users.saveAndFlush(user);
        for (String email : List.of(owner.email(), "unknown@example.test", disabled.email())) {
            String password = email.equals(disabled.email()) ? PASSWORD : "incorrect-password";
            var response = request("POST", "/api/v1/auth/login", json.writeValueAsString(java.util.Map.of("email", email, "password", password)), null);
            error(response, 401, "INVALID_CREDENTIALS");
        }
        error(request("GET", "/api/v1/auth/me", null, disabled.token()), 401, "INVALID_TOKEN");
    }

    @Test
    void missingAndMalformedBearerTokensUseSecurityApiErrors() throws Exception {
        error(request("GET", "/api/v1/auth/me", null, null), 401, "AUTHENTICATION_REQUIRED");
        error(request("GET", "/api/v1/auth/me", null, "invalid.jwt.value"), 401, "INVALID_TOKEN");
        error(request("POST", "/api/v1/customers", "{}", null), 401, "AUTHENTICATION_REQUIRED");
    }

    @Test
    void meContainsOnlySafeCurrentIdentityInformation() throws Exception {
        var response = request("GET", "/api/v1/auth/me", null, owner.token());
        assertThat(response.statusCode()).isEqualTo(200);
        var body = json.readTree(response.body());
        assertThat(body.size()).isEqualTo(3);
        assertThat(body.path("id").asString()).isEqualTo(owner.userId().toString());
        assertThat(body.path("customerId").asString()).isEqualTo(owner.customerId().toString());
        assertThat(body.path("roles").get(0).asString()).isEqualTo("USER");
        assertThat(response.body()).doesNotContain("email", "password", "balance", "securityVersion");
    }

    @Test
    void userReadsOnlyOwnedCustomerAccountBalanceAndStatements() throws Exception {
        for (String path : List.of("/api/v1/customers/" + owner.customerId(), "/api/v1/accounts/" + owner.accountId(),
                "/api/v1/accounts/" + owner.accountId() + "/balance", "/api/v1/accounts/" + owner.accountId() + "/transactions")) {
            assertThat(request("GET", path, null, owner.token()).statusCode()).isEqualTo(200);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/balance", "/transactions"})
    void foreignAndMissingAccountsAreIndistinguishable(String suffix) throws Exception {
        var foreign = error(request("GET", "/api/v1/accounts/" + other.accountId() + suffix, null, owner.token()), 404, "ACCOUNT_NOT_FOUND");
        var missing = error(request("GET", "/api/v1/accounts/" + UUID.randomUUID() + suffix, null, owner.token()), 404, "ACCOUNT_NOT_FOUND");
        assertThat(foreign.path("message").asString()).isEqualTo(missing.path("message").asString());
        assertThat(foreign.path("path").asString()).isEqualTo(missing.path("path").asString());
    }

    @Test
    void foreignAndMissingCustomersHaveTheSame404() throws Exception {
        var foreign = error(request("GET", "/api/v1/customers/" + other.customerId(), null, owner.token()), 404, "CUSTOMER_NOT_FOUND");
        var missing = error(request("GET", "/api/v1/customers/" + UUID.randomUUID(), null, owner.token()), 404, "CUSTOMER_NOT_FOUND");
        assertThat(foreign.path("message").asString()).isEqualTo(missing.path("message").asString());
    }

    @Test
    void adminCreatesCustomersAndAccountsButDoesNotInheritUserReadPermissions() throws Exception {
        var created = request("POST", "/api/v1/customers", "{\"firstName\":\"Admin\",\"lastName\":\"Created\"}", admin.token());
        assertThat(created.statusCode()).isEqualTo(201);
        String customerId = json.readTree(created.body()).path("id").asString();
        var opened = request("POST", "/api/v1/accounts", "{\"customerId\":\"" + customerId + "\",\"type\":\"SAVINGS\",\"currencyCode\":\"MXN\"}", admin.token());
        assertThat(opened.statusCode()).isEqualTo(201);
        error(request("GET", "/api/v1/accounts/" + owner.accountId() + "/balance", null, admin.token()), 403, "FORBIDDEN");
        error(request("GET", "/api/v1/customers/" + owner.customerId(), null, admin.token()), 403, "FORBIDDEN");
        error(request("POST", "/api/v1/customers", "{}", owner.token()), 403, "FORBIDDEN");
        error(request("POST", "/api/v1/accounts", "{}", owner.token()), 403, "FORBIDDEN");
    }

    @Test
    void combinedRolesStillEnforceOwnership() throws Exception {
        assertThat(request("GET", "/api/v1/accounts/" + both.accountId(), null, both.token()).statusCode()).isEqualTo(200);
        error(request("GET", "/api/v1/accounts/" + owner.accountId(), null, both.token()), 404, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void rolesAreLoadedFromDatabaseForEveryRequest() throws Exception {
        var changed = fixture(Set.of(AuthRole.USER, AuthRole.ADMIN));
        jdbc.update("DELETE FROM auth_user_roles WHERE auth_user_id = ? AND role = 'ADMIN'", changed.userId());
        error(request("POST", "/api/v1/customers", "{}", changed.token()), 403, "FORBIDDEN");
        assertThat(request("GET", "/api/v1/accounts/" + changed.accountId(), null, changed.token()).statusCode()).isEqualTo(200);
    }

    @Test
    void changedSecurityVersionRejectsPreviouslyIssuedJwt() throws Exception {
        var revoked = fixture(Set.of(AuthRole.USER));
        var user = users.findById(revoked.userId()).orElseThrow();
        user.revokeTokens(clock.instant());
        users.saveAndFlush(user);
        error(request("GET", "/api/v1/auth/me", null, revoked.token()), 401, "INVALID_TOKEN");
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "issuer", "audience", "futureIat", "futureNbf", "missingExp", "missingIat", "missingNbf", "missingJti", "invalidVersion", "unknownSubject"})
    void signedButInvalidClaimsAreRejected(String invalid) throws Exception {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().subject(invalid.equals("unknownSubject") ? UUID.randomUUID().toString() : owner.userId().toString())
                .issuer(invalid.equals("issuer") ? "other-issuer" : "fincore-test")
                .audience(List.of(invalid.equals("audience") ? "other-audience" : "fincore-test-api"))
                .claim("ver", invalid.equals("invalidVersion") ? "0" : 0L);
        if (!invalid.equals("missingIat")) { claims.issuedAt(invalid.equals("expired") ? now.minusSeconds(600) : invalid.equals("futureIat") ? now.plusSeconds(120) : now); }
        if (!invalid.equals("missingNbf")) { claims.notBefore(invalid.equals("expired") ? now.minusSeconds(600) : invalid.equals("futureNbf") ? now.plusSeconds(120) : now); }
        if (!invalid.equals("missingExp")) { claims.expiresAt(invalid.equals("expired") ? now.minusSeconds(120) : now.plusSeconds(900)); }
        if (!invalid.equals("missingJti")) { claims.id(UUID.randomUUID().toString()); }
        String token = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims.build())).getTokenValue();
        error(request("GET", "/api/v1/auth/me", null, token), 401, "INVALID_TOKEN");
    }

    @Test
    void jwtSignedByADifferentRsaKeyIsRejected() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var key = new RSAKey.Builder((RSAPublicKey) pair.getPublic()).privateKey((RSAPrivateKey) pair.getPrivate()).build();
        var wrongEncoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
        var claims = decoder.decode(owner.token()).getClaims();
        String token = wrongEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(),
                JwtClaimsSet.builder().claims(values -> values.putAll(claims)).build())).getTokenValue();
        error(request("GET", "/api/v1/auth/me", null, token), 401, "INVALID_TOKEN");
    }

    @Test
    void unsignedJwtAndUnexpectedRoutesAreDeniedAndDefaultDoesNotExposeSwagger() throws Exception {
        String unsigned = owner.token().substring(0, owner.token().lastIndexOf('.') + 1);
        error(request("GET", "/api/v1/auth/me", null, unsigned), 401, "INVALID_TOKEN");
        error(request("GET", "/api/v1/not-declared-private-input?secret=value", null, owner.token()), 403, "FORBIDDEN");
        error(request("PUT", "/api/v1/customers/" + owner.customerId(), "{}", owner.token()), 403, "FORBIDDEN");
        assertThat(request("GET", "/v3/api-docs", null, admin.token()).statusCode()).isEqualTo(403);
        assertThat(request("GET", "/swagger-ui/index.html", null, admin.token()).statusCode()).isEqualTo(403);
    }

    @Test
    void transfersRequireAuthenticationWithoutChangingTheirPhase7ABusinessBehavior() throws Exception {
        error(request("POST", "/api/v1/transfers", "{}", null), 401, "AUTHENTICATION_REQUIRED");
        error(request("GET", "/api/v1/transfers/" + UUID.randomUUID(), null, null), 401, "AUTHENTICATION_REQUIRED");
        error(request("GET", "/api/v1/transfers/" + UUID.randomUUID(), null, owner.token()), 404, "TRANSFER_NOT_FOUND");
    }

    @Test
    void storedHashIsSaltedAndContactEmailRemainsIndependent() {
        var user = users.findById(owner.userId()).orElseThrow();
        assertThat(user.getPasswordHash()).startsWith("{argon2id}$argon2id$").doesNotContain(PASSWORD);
        assertThat(passwords.matches(PASSWORD, user.getPasswordHash())).isTrue();
        assertThat(user.getPasswordHash()).isNotEqualTo(users.findById(other.userId()).orElseThrow().getPasswordHash());
        assertThat(jdbc.queryForObject("SELECT email FROM customers WHERE id = ?", String.class, owner.customerId())).isEqualTo("contact@example.test");
        assertThat(user.getLoginEmail()).isNotEqualTo("contact@example.test");
    }

    @Test
    void loginPreservesLeadingAndTrailingPasswordSpaces() throws Exception {
        String exact = "  significant spaces password  ";
        String email = UUID.randomUUID() + "@spaces.example.test";
        provisioning.provision(email, exact, null, Set.of(AuthRole.ADMIN));
        assertThat(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(java.util.Map.of("email", email, "password", exact)), null).statusCode()).isEqualTo(200);
        error(request("POST", "/api/v1/auth/login",
                json.writeValueAsString(java.util.Map.of("email", email, "password", exact.strip())), null), 401, "INVALID_CREDENTIALS");
        assertThatThrownBy(() -> provisioning.provision("blank@example.test", "            ", null, Set.of(AuthRole.ADMIN)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void wrongAlgorithmIsRejectedEvenWithASignedToken() throws Exception {
        var header = new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.HS256);
        var now = clock.instant();
        var jwt = new com.nimbusds.jwt.SignedJWT(header, new com.nimbusds.jwt.JWTClaimsSet.Builder()
                .subject(owner.userId().toString()).issuer("fincore-test").audience("fincore-test-api")
                .issueTime(java.util.Date.from(now)).notBeforeTime(java.util.Date.from(now))
                .expirationTime(java.util.Date.from(now.plusSeconds(900))).jwtID(UUID.randomUUID().toString())
                .claim("ver", 0L).build());
        byte[] secret = new byte[32];
        new java.security.SecureRandom().nextBytes(secret);
        jwt.sign(new com.nimbusds.jose.crypto.MACSigner(secret));
        error(request("GET", "/api/v1/auth/me", null, jwt.serialize()), 401, "INVALID_TOKEN");
    }

    @Test
    void databaseEnforcesLoginAndCustomerUniquenessAndRoleValues() {
        assertThatThrownBy(() -> duplicateUser(UUID.randomUUID(), owner.email(), null)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> duplicateUser(UUID.randomUUID(), UUID.randomUUID() + "@unique.example.test", owner.customerId())).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO auth_user_roles (auth_user_id, role) VALUES (?, 'ROOT')", owner.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE auth_users SET password_hash = 'plaintext' WHERE id = ?", owner.userId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private void duplicateUser(UUID id, String email, UUID customerId) {
        jdbc.update("INSERT INTO auth_users (id, login_email, password_hash, status, customer_id, security_version, created_at, updated_at, version) "
                + "SELECT ?, ?, password_hash, status, ?, security_version, created_at, updated_at, version FROM auth_users WHERE id = ?",
                id, email, customerId, owner.userId());
    }

    @Test
    void provisioningDoesNotRelinkByEmailOrOverwriteExistingPasswords() {
        assertThatThrownBy(() -> provisioning.provision(owner.email(), "replacement-password", other.customerId(), Set.of(AuthRole.USER)))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> provisioning.provision("new@example.test", PASSWORD, null, Set.of(AuthRole.USER)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(users.findById(owner.userId()).orElseThrow().getCustomerId()).isEqualTo(owner.customerId());
        assertThat(passwords.matches(PASSWORD, users.findById(owner.userId()).orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void v8UpgradesARealV7SchemaWithoutChangingExistingCustomerOrMigrationChecksums() {
        String upgrade = "auth_upgrade_test_" + UUID.randomUUID().toString().replace("-", "");
        try {
            var base = Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade)
                    .locations("classpath:db/migration").target("7").load();
            base.migrate();
            UUID customer = UUID.randomUUID();
            jdbc.update("INSERT INTO " + upgrade + ".customers (id, first_name, last_name, email, created_at, updated_at, version) "
                    + "VALUES (?, 'Existing', 'Customer', 'legacy@example.test', now(), now(), 0)", customer);
            String before = jdbc.queryForObject("SELECT to_jsonb(c)::text FROM " + upgrade + ".customers c", String.class);
            var checksums = jdbc.queryForList("SELECT checksum FROM " + upgrade + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", Integer.class);
            Flyway.configure().dataSource(dataSource).schemas(upgrade).defaultSchema(upgrade).locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT to_jsonb(c)::text FROM " + upgrade + ".customers c", String.class)).isEqualTo(before);
            assertThat(jdbc.queryForList("SELECT checksum FROM " + upgrade + ".flyway_schema_history WHERE version <> '8' ORDER BY installed_rank", Integer.class)).isEqualTo(checksums);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + upgrade + ".auth_users", Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + upgrade + ".auth_user_roles", Integer.class)).isZero();
        } finally { jdbc.execute("DROP SCHEMA " + upgrade + " CASCADE"); }
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("8");
    }

    @AfterAll
    void cleanupOnlyThisGeneratedSchema() {
        if (!SCHEMA.matches("security_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe test schema"); }
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
}
