package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import io.github.alejandro117b.fincore.FinCoreApplication;
import io.github.alejandro117b.fincore.auth.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Upgrade real V8 financial history before starting the V9 server. No disabled SQL or HTTP guards. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(60)
class TransferIdentityMigrationTests {
    private static final String SCHEMA = "id_migration_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final String UPGRADE = SCHEMA + "_upgrade";
    private static final Instant AT = Instant.parse("2026-01-01T12:00:00Z");
    private final UUID sender = UUID.randomUUID(), recipient = UUID.randomUUID(), outsider = UUID.randomUUID();
    private final UUID source = UUID.randomUUID(), destination = UUID.randomUUID(), transfer = UUID.randomUUID();
    private final String legacyKey = "legacy-success-" + UUID.randomUUID();
    private final String rejectedKey = "legacy-rejection-" + UUID.randomUUID();
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    private ConfigurableApplicationContext server;
    private JdbcTemplate upgraded;
    private String senderToken, recipientToken, outsiderToken;
    private UUID senderUser;
    private Map<String, List<String>> before;
    private List<Integer> checksums;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry p) {
        p.add("spring.flyway.schemas", () -> SCHEMA);
        p.add("spring.flyway.default-schema", () -> SCHEMA);
        p.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        p.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @BeforeAll
    void upgradeAndStartServer() throws Exception {
        Flyway.configure().dataSource(dataSource).schemas(UPGRADE).defaultSchema(UPGRADE)
                .locations("classpath:db/migration").target("8").load().migrate();
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            var old = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            old.execute("SET LOCAL search_path TO " + UPGRADE);
            for (UUID id : List.of(sender, recipient, outsider)) {
                old.update("INSERT INTO customers (id,first_name,last_name,created_at,updated_at,version) VALUES (?, 'Historical', 'Customer', ?, ?, 0)",
                        id, Timestamp.from(AT), Timestamp.from(AT));
            }
            for (var account : List.of(Map.entry(source, sender), Map.entry(destination, recipient))) {
                old.update("INSERT INTO accounts (id,customer_id,type,status,currency_code,created_at,updated_at,version) "
                        + "VALUES (?,?,'CHECKING','ACTIVE','MXN',?,?,0)", account.getKey(), account.getValue(), Timestamp.from(AT), Timestamp.from(AT));
            }
            UUID debit = UUID.randomUUID(), credit = UUID.randomUUID(), journal = UUID.randomUUID();
            for (var ledger : List.of(Map.entry(debit, source), Map.entry(credit, destination))) {
                old.update("INSERT INTO ledger_accounts (id,account_id,category,currency_code,created_at) VALUES (?,?,'LIABILITY','MXN',?)",
                        ledger.getKey(), ledger.getValue(), Timestamp.from(AT));
            }
            UUID cash = UUID.randomUUID(), funding = UUID.randomUUID();
            old.update("INSERT INTO ledger_accounts (id,system_code,category,currency_code,created_at) VALUES (?,'UPGRADE_CASH','ASSET','MXN',?)",
                    cash, Timestamp.from(AT));
            old.update("INSERT INTO journal_transactions (id,currency_code,status,reference,created_at) VALUES (?,'MXN','DRAFT','historical funding',?)",
                    funding, Timestamp.from(AT));
            old.update("INSERT INTO ledger_entries (id,journal_transaction_id,ledger_account_id,line_number,side,amount,currency_code) "
                    + "VALUES (?,?,?,1,'DEBIT',500,'MXN'), (?,?,?,2,'CREDIT',500,'MXN')",
                    UUID.randomUUID(), funding, cash, UUID.randomUUID(), funding, debit);
            old.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = created_at WHERE id = ?", funding);
            // Historical V8 fixture uses active SQL double-entry/journal guards and a funded source.
            old.update("INSERT INTO journal_transactions (id,currency_code,status,reference,created_at) VALUES (?,'MXN','DRAFT','historical',?)",
                    journal, Timestamp.from(AT));
            old.update("INSERT INTO ledger_entries (id,journal_transaction_id,ledger_account_id,line_number,side,amount,currency_code) "
                    + "VALUES (?,?,?,1,'DEBIT',100,'MXN'), (?,?,?,2,'CREDIT',100,'MXN')",
                    UUID.randomUUID(), journal, debit, UUID.randomUUID(), journal, credit);
            old.update("UPDATE journal_transactions SET status = 'POSTED', posted_at = created_at WHERE id = ?", journal);
            old.update("INSERT INTO transfers (id,source_account_id,destination_account_id,amount,currency_code,reference,journal_transaction_id,created_at,completed_at) "
                    + "VALUES (?,?,?,100,'MXN','historical',?,?,?)", transfer, source, destination, journal, Timestamp.from(AT), Timestamp.from(AT));
            UUID reservation = UUID.randomUUID(), rejection = UUID.randomUUID();
            old.update("INSERT INTO transfer_idempotency_records (id,idempotency_key,request_hash,hash_version,status,created_at) "
                    + "VALUES (?,?,?,1,'RESERVED',?), (?,?,?,1,'RESERVED',?)", reservation, legacyKey,
                    TransferRequestHasher.hash(source, destination, new BigDecimal("100"), "MXN"), Timestamp.from(AT),
                    rejection, rejectedKey, "b".repeat(64), Timestamp.from(AT));
            old.update("UPDATE transfer_idempotency_records SET status = 'SUCCEEDED', transfer_id = ?, resolved_at = created_at WHERE id = ?", transfer, reservation);
            old.update("UPDATE transfer_idempotency_records SET status = 'REJECTED', failure_code = 'INSUFFICIENT_FUNDS', resolved_at = created_at WHERE id = ?", rejection);
            connection.commit();
        }
        before = snapshot(false);
        checksums = jdbc.queryForList("SELECT checksum FROM " + UPGRADE + ".flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", Integer.class);
        Flyway.configure().dataSource(dataSource).schemas(UPGRADE).defaultSchema(UPGRADE)
                .locations("classpath:db/migration").load().migrate();
        assertThat(snapshot(true)).isEqualTo(before);
        server = new SpringApplicationBuilder(FinCoreApplication.class).web(WebApplicationType.SERVLET).run(
                "--spring.profiles.active=", "--POSTGRES_DB=fincore", "--server.port=0",
                "--fincore.demo.seed.enabled=false", "--spring.flyway.schemas=" + UPGRADE,
                "--spring.flyway.default-schema=" + UPGRADE, "--spring.jpa.properties.hibernate.default_schema=" + UPGRADE,
                "--spring.datasource.hikari.connection-init-sql=SET search_path TO " + UPGRADE);
        upgraded = server.getBean(JdbcTemplate.class);
        senderToken = provision(sender);
        recipientToken = provision(recipient);
        outsiderToken = provision(outsider);
        senderUser = server.getBean(AuthUserRepository.class).findByCustomerId(sender).orElseThrow().getId();
    }

    private String provision(UUID customer) {
        UUID id = server.getBean(AuthUserProvisioningService.class).provision(customer + "@upgrade.example.test",
                "upgrade-test-password", customer, Set.of(AuthRole.USER));
        return server.getBean(JwtTokenService.class).issue(server.getBean(AuthUserRepository.class).findById(id).orElseThrow());
    }

    private Map<String, List<String>> snapshot(boolean v9) {
        var result = new java.util.LinkedHashMap<String, List<String>>();
        for (String table : List.of("customers", "accounts", "ledger_accounts", "journal_transactions", "ledger_entries", "transfers", "transfer_idempotency_records")) {
            String projection = v9 && table.equals("transfer_idempotency_records") ? "(to_jsonb(t) - 'requester_user_id')::text" : "to_jsonb(t)::text";
            result.put(table, jdbc.queryForList("SELECT " + projection + " FROM " + UPGRADE + "." + table + " t ORDER BY id", String.class));
        }
        return result;
    }

    private HttpResponse<String> request(String token, String method, String path, String key) throws Exception {
        int port = ((WebServerApplicationContext) server).getWebServer().getPort();
        var builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).header("Accept", "application/json");
        if (key != null) { builder.header("Idempotency-Key", key).header("Content-Type", "application/json"); }
        String body = "{\"sourceAccountId\":\"" + source + "\",\"destinationAccountId\":\"" + destination
                + "\",\"amount\":\"100.00\",\"currencyCode\":\"MXN\"}";
        return HttpClient.newHttpClient().send(builder.method(method, key == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void v9PreservesEveryOriginalValueAndLeavesRequestersUnattributed() {
        assertThat(snapshot(true)).isEqualTo(before);
        assertThat(upgraded.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE requester_user_id IS NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT checksum FROM " + UPGRADE + ".flyway_schema_history WHERE version <> '9' ORDER BY installed_rank", Integer.class)).isEqualTo(checksums);
        assertThat(server.getBean(Flyway.class).info().current().getVersion().getVersion()).isEqualTo("9");
        var balances = server.getBean(io.github.alejandro117b.fincore.ledger.LedgerBalanceService.class);
        UUID sourceLedger = upgraded.queryForObject("SELECT id FROM ledger_accounts WHERE account_id = ?", UUID.class, source);
        UUID destinationLedger = upgraded.queryForObject("SELECT id FROM ledger_accounts WHERE account_id = ?", UUID.class, destination);
        assertThat(balances.getBalance(sourceLedger)).isEqualByComparingTo("400");
        assertThat(balances.getBalance(destinationLedger)).isEqualByComparingTo("100");
    }

    @Test
    void historicalTransfersAreVisibleOnlyToCurrentParticipantsThroughRealJwtHttp() throws Exception {
        for (String token : List.of(senderToken, recipientToken)) {
            var response = request(token, "GET", "/api/v1/transfers/" + transfer, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(JsonMapper.builder().build().readTree(response.body()).path("id").asString()).isEqualTo(transfer.toString());
        }
        var foreign = request(outsiderToken, "GET", "/api/v1/transfers/" + transfer, null);
        var missing = request(outsiderToken, "GET", "/api/v1/transfers/" + UUID.randomUUID(), null);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(missing.statusCode()).isEqualTo(404);
        var json = JsonMapper.builder().build();
        assertThat(json.readTree(foreign.body()).path("code").asString()).isEqualTo("TRANSFER_NOT_FOUND");
        assertThat(json.readTree(foreign.body()).path("message")).isEqualTo(json.readTree(missing.body()).path("message"));
    }

    @Test
    void historicalSuccessAndRejectionKeysReturnOnlyTheGenericUnavailableError() throws Exception {
        for (String key : List.of(legacyKey, rejectedKey)) {
            var response = request(senderToken, "POST", "/api/v1/transfers", key);
            assertThat(response.statusCode()).isEqualTo(409);
            var error = JsonMapper.builder().build().readTree(response.body());
            assertThat(error.path("code").asString()).isEqualTo("IDEMPOTENCY_KEY_UNAVAILABLE");
            assertThat(error.path("message").asString()).isEqualTo("Idempotency key is unavailable.");
            assertThat(response.body()).doesNotContain("legacy", "histor", key, transfer.toString(), "request_hash", "SUCCEEDED", "REJECTED");
            assertThat(error.path("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        }
        assertThat(snapshot(true)).isEqualTo(before);
    }

    @Test
    void sqlRetiresHistoricalKeysEvenWithADifferentSearchPathAndTranslatesItsGuard() {
        // Qualified INSERT intentionally uses the other schema's search_path from the autowired pool.
        assertSqlState(catchThrowable(() -> jdbc.update("INSERT INTO " + UPGRADE + ".transfer_idempotency_records "
                + "(id,requester_user_id,idempotency_key,request_hash,hash_version,status,created_at) VALUES (?,?,?, ?,1,'RESERVED',now())",
                UUID.randomUUID(), senderUser, legacyKey, "a".repeat(64))), "P7001");
        var transactions = new org.springframework.transaction.support.TransactionTemplate(server.getBean(org.springframework.transaction.PlatformTransactionManager.class));
        assertThatThrownBy(() -> transactions.executeWithoutResult(tx -> server.getBean(TransferIdempotencyRepository.class)
                .reserve(senderUser, legacyKey, "a".repeat(64), Instant.now())))
                .isInstanceOf(io.github.alejandro117b.fincore.api.ApiException.class).hasMessage("Idempotency key is unavailable.");
    }

    @Test
    void historicalNullRequesterCannotBePromotedAndTerminalHistoryCannotBeMutated() {
        for (String mutation : List.of("UPDATE transfer_idempotency_records SET requester_user_id = ? WHERE idempotency_key = '" + legacyKey + "'",
                "UPDATE transfer_idempotency_records SET requester_user_id = ? WHERE idempotency_key = '" + rejectedKey + "'")) {
            assertSqlState(catchThrowable(() -> upgraded.update(mutation, senderUser)), "23514");
        }
        assertThat(snapshot(true)).isEqualTo(before);
    }

    private void assertSqlState(Throwable failure, String expected) {
        assertThat(failure).isNotNull();
        Throwable root = failure;
        while (root.getCause() != null) { root = root.getCause(); }
        assertThat(root).isInstanceOf(java.sql.SQLException.class);
        assertThat(((java.sql.SQLException) root).getSQLState()).isEqualTo(expected);
    }

    @AfterAll
    void cleanupOnlyGeneratedSchemas() {
        if (server != null) { server.close(); }
        if (!SCHEMA.matches("id_migration_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe fixture schema"); }
        jdbc.execute("DROP SCHEMA IF EXISTS " + UPGRADE + " CASCADE");
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }
}
