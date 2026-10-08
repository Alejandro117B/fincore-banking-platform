package io.github.alejandro117b.fincore.api;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountRepository;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.ledger.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(45)
class ApiHttpIntegrationTests {
    private static final String SCHEMA = "api_test_" + UUID.randomUUID().toString().replace("-", "");
    private static final Instant AT = Instant.parse("2026-01-01T12:00:00.123456Z");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        properties.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
        properties.add("springdoc.api-docs.enabled", () -> true);
        properties.add("springdoc.swagger-ui.enabled", () -> true);
    }

    @LocalServerPort private int port;
    @Autowired private JsonMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private LedgerPostingService posting;
    @Autowired private LedgerAccountRepository ledgerAccounts;
    @Autowired private AccountRepository accounts;
    @Autowired private Flyway flyway;
    @PersistenceContext private EntityManager em;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
            .version(HttpClient.Version.HTTP_1_1).build();

    private TransactionTemplate tx() {
        TransactionTemplate tx = new TransactionTemplate(manager);
        tx.setTimeout(15);
        return tx;
    }

    private HttpResponse<String> request(String method, String path, String body, String... headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (body != null) { builder.header("Content-Type", "application/json"); }
        for (int index = 0; index < headers.length; index += 2) { builder.header(headers[index], headers[index + 1]); }
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) {
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        return json.readTree(response.body());
    }

    private UUID customer() throws Exception {
        var response = request("POST", "/api/v1/customers", "{\"firstName\":\"Ana\",\"lastName\":\"Perez\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).path("id").asString());
    }

    private UUID account(UUID customer, String currency) throws Exception {
        var response = request("POST", "/api/v1/accounts", "{\"customerId\":\"" + customer
                + "\",\"type\":\"CHECKING\",\"currencyCode\":\"" + currency + "\"}");
        assertThat(response.statusCode()).isEqualTo(201);
        return UUID.fromString(body(response).path("id").asString());
    }

    private record Pair(UUID customer, UUID source, UUID destination, UUID debit, UUID credit) {
    }

    private Pair pair() throws Exception {
        UUID customer = customer();
        UUID source = account(customer, "MXN");
        UUID destination = account(customer, "MXN");
        return new Pair(customer, source, destination, ledger(source), ledger(destination));
    }

    private UUID ledger(UUID account) {
        return jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE account_id = ?", UUID.class, account);
    }

    private void fund(UUID ledgerId, String amount) {
        tx().executeWithoutResult(status -> {
            LedgerAccount credit = ledgerAccounts.findById(ledgerId).orElseThrow();
            LedgerAccount cash = LedgerAccount.internal("CASH_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    LedgerAccountCategory.ASSET, credit.getCurrencyCode(), AT);
            em.persist(cash);
            JournalTransaction journal = JournalTransaction.draft(credit.getCurrencyCode(), "http-technical-fixture", AT);
            posting.post(journal, List.of(
                    LedgerEntry.create(journal, cash, 1, EntrySide.DEBIT, new BigDecimal(amount)),
                    LedgerEntry.create(journal, credit, 2, EntrySide.CREDIT, new BigDecimal(amount))), AT.plusSeconds(1));
        });
    }

    private String transferBody(Pair pair, String amount, String reference) {
        return "{\"sourceAccountId\":\"" + pair.source() + "\",\"destinationAccountId\":\"" + pair.destination()
                + "\",\"amount\":\"" + amount + "\",\"currencyCode\":\"MXN\",\"reference\":\"" + reference + "\"}";
    }

    private HttpResponse<String> transfer(Pair pair, String amount, String key) throws Exception {
        return request("POST", "/api/v1/transfers", transferBody(pair, amount, "original"), "Idempotency-Key", key);
    }

    private String balance(UUID id) throws Exception {
        var response = request("GET", "/api/v1/accounts/" + id + "/balance", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode value = body(response).path("postedBalance");
        assertThat(value.isString()).isTrue();
        return value.asString();
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private void error(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode error = body(response);
        assertThat(error.path("status").asInt()).isEqualTo(status);
        assertThat(error.path("code").asString()).isEqualTo(code);
        assertThat(error.path("requestId").asString()).isEqualTo(response.headers().firstValue("X-Request-Id").orElseThrow());
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.body()).doesNotContain("stackTrace", "SQLSTATE", "PostgreSQL", "Hibernate", "constraint");
    }

    @Test
    void customerAndAccountCreationAreCommittedAndExposeOnlyTheDtoContract() throws Exception {
        UUID customer = customer();
        var customerResponse = request("GET", "/api/v1/customers/" + customer, null);
        assertThat(customerResponse.statusCode()).isEqualTo(200);
        JsonNode customerJson = body(customerResponse);
        assertThat(customerJson.path("email").isNull()).isTrue();
        assertThat(customerJson.has("version")).isFalse();
        assertThat(customerJson.has("accounts")).isFalse();
        UUID account = account(customer, "mxn");
        var response = request("GET", "/api/v1/accounts/" + account, null);
        JsonNode accountJson = body(response);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(accountJson.path("customerId").asString()).isEqualTo(customer.toString());
        assertThat(accountJson.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(accountJson.path("currencyCode").asString()).isEqualTo("MXN");
        assertThat(accountJson.has("balance")).isFalse();
        assertThat(accountJson.has("version")).isFalse();
        assertThat(accountJson.has("ledgerAccount")).isFalse();
        assertThat(jdbc.queryForObject("SELECT category FROM ledger_accounts WHERE account_id = ?", String.class, account)).isEqualTo("LIABILITY");
        assertThat(balance(account)).isEqualTo("0.0000");
        var history = request("GET", "/api/v1/accounts/" + account + "/transactions", null);
        assertThat(body(history).path("items").size()).isZero();
        assertThat(body(history).path("hasMore").asBoolean()).isFalse();
        assertThat(body(history).path("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidCustomerAccountAndMissingResourcesHaveControlledResponses() throws Exception {
        error(request("POST", "/api/v1/customers", "{\"firstName\":\"\",\"lastName\":\"Perez\"}"), 400, "VALIDATION_ERROR");
        // @Email may accept a local domain; the existing Customer factory requires a dotted contact address.
        var contact = request("POST", "/api/v1/customers", "{\"firstName\":\"Ana\",\"lastName\":\"Perez\",\"email\":\"ana@local\"}");
        assertThat(contact.statusCode()).isIn(400, 422);
        error(request("POST", "/api/v1/accounts", "{\"customerId\":\"" + UUID.randomUUID()
                + "\",\"type\":\"CHECKING\",\"currencyCode\":\"MXN\"}"), 404, "CUSTOMER_NOT_FOUND");
        UUID customer = customer();
        error(request("POST", "/api/v1/accounts", "{\"customerId\":\"" + customer
                + "\",\"type\":\"CHECKING\",\"currencyCode\":\"ZZZ\"}"), 422, "INVALID_CURRENCY");
        for (String resource : List.of("customers", "accounts", "transfers")) {
            error(request("GET", "/api/v1/" + resource + "/" + UUID.randomUUID(), null), 404,
                    resource.equals("customers") ? "CUSTOMER_NOT_FOUND" : resource.equals("accounts") ? "ACCOUNT_NOT_FOUND" : "TRANSFER_NOT_FOUND");
            error(request("GET", "/api/v1/" + resource + "/invalid-secret-path", null), 400, "INVALID_UUID");
        }
    }

    @Test
    void accountAndLedgerCreationRollBackTogetherAndDiagnosticsStayServerSide(CapturedOutput output) throws Exception {
        UUID owner = customer();
        long accountsBefore = count("accounts");
        long ledgersBefore = count("ledger_accounts");
        jdbc.execute("""
                CREATE FUNCTION test_http_fail_ledger() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced account ledger failure private_constraint SQLSTATE' USING ERRCODE = 'XX000'; END; $$
                """);
        jdbc.execute("CREATE TRIGGER test_http_fail_ledger BEFORE INSERT ON ledger_accounts FOR EACH ROW EXECUTE FUNCTION test_http_fail_ledger()");
        HttpResponse<String> response;
        try {
            response = request("POST", "/api/v1/accounts", "{\"customerId\":\"" + owner + "\",\"type\":\"CHECKING\",\"currencyCode\":\"MXN\"}");
            error(response, 500, "INTERNAL_ERROR");
        } finally {
            jdbc.execute("DROP TRIGGER test_http_fail_ledger ON ledger_accounts");
            jdbc.execute("DROP FUNCTION test_http_fail_ledger()");
        }
        assertThat(count("accounts")).isEqualTo(accountsBefore);
        assertThat(count("ledger_accounts")).isEqualTo(ledgersBefore);
        assertThat(response.body()).doesNotContain("private_constraint", "forced account ledger failure");
        assertThat(output.getAll()).contains("forced account ledger failure", response.headers().firstValue("X-Request-Id").orElseThrow());
    }

    @Test
    void anExistingAccountWithoutLedgerIsNotMisrepresentedAsZeroBalance() throws Exception {
        UUID bank = tx().execute(status -> {
            Customer owner = Customer.create("Legacy", "Customer", null, AT);
            Account account = Account.create(owner, AccountType.CHECKING, "MXN", AT);
            em.persist(owner);
            em.persist(account);
            em.flush();
            return account.getId();
        });
        error(request("GET", "/api/v1/accounts/" + bank + "/balance", null), 409, "ACCOUNT_NOT_READY");
        error(request("GET", "/api/v1/accounts/" + bank + "/transactions", null), 409, "ACCOUNT_NOT_READY");
        UUID destination = account(customer(), "MXN");
        error(request("POST", "/api/v1/transfers", "{\"sourceAccountId\":\"" + bank + "\",\"destinationAccountId\":\""
                + destination + "\",\"amount\":\"1\",\"currencyCode\":\"MXN\"}", "Idempotency-Key", UUID.randomUUID().toString()),
                409, "ACCOUNT_NOT_READY");
    }

    @Test
    void transferSuccessReplayReferenceAndLedgerBalancesWorkOverRealHttp() throws Exception {
        Pair pair = pair();
        fund(pair.debit(), "800");
        String key = UUID.randomUUID().toString();
        var first = transfer(pair, "500.00", key);
        assertThat(first.statusCode()).isEqualTo(201);
        JsonNode result = body(first);
        assertThat(result.path("amount").isString()).isTrue();
        assertThat(result.path("amount").asString()).isEqualTo("500.0000");
        assertThat(balance(pair.source())).isEqualTo("300.0000");
        assertThat(balance(pair.destination())).isEqualTo("500.0000");
        var replay = request("POST", "/api/v1/transfers", transferBody(pair, "500", "different-reference"), "Idempotency-Key", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(body(replay)).isEqualTo(result);
        assertThat(replay.headers().firstValue("Location")).isEqualTo(first.headers().firstValue("Location"));
        assertThat(replay.headers().firstValue("X-Request-Id")).isNotEqualTo(first.headers().firstValue("X-Request-Id"));
        var lookup = request("GET", first.headers().firstValue("Location").orElseThrow(), null);
        assertThat(body(lookup)).isEqualTo(result);
        error(transfer(pair, "501", key), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(new BigDecimal(balance(pair.source())).add(new BigDecimal(balance(pair.destination())))).isEqualByComparingTo("800");
        var history = body(request("GET", "/api/v1/accounts/" + pair.source() + "/transactions", null));
        JsonNode movement = history.path("items").get(0);
        assertThat(movement.path("transferId").asString()).isEqualTo(result.path("id").asString());
        assertThat(movement.path("netAmount").asString()).isEqualTo("-500.0000");
        assertThat(movement.path("debitAmount").asString()).isEqualTo("500.0000");
        assertThat(movement.path("creditAmount").asString()).isEqualTo("0.0000");
    }

    @Test
    void insufficientFundsIsReallyCommittedAndReplayedAfterFunding() throws Exception {
        Pair pair = pair();
        String key = UUID.randomUUID().toString();
        var first = transfer(pair, "500", key);
        error(first, 409, "INSUFFICIENT_FUNDS");
        assertThat(jdbc.queryForMap("SELECT status,failure_code,transfer_id,resolved_at FROM transfer_idempotency_records WHERE idempotency_key = ?", key))
                .containsEntry("status", "REJECTED").containsEntry("failure_code", "INSUFFICIENT_FUNDS").containsEntry("transfer_id", null);
        fund(pair.debit(), "800");
        var replay = transfer(pair, "500.00", key);
        error(replay, 409, "INSUFFICIENT_FUNDS");
        assertThat(body(replay).path("message")).isEqualTo(body(first).path("message"));
        assertThat(balance(pair.source())).isEqualTo("800.0000");
        assertThat(balance(pair.destination())).isEqualTo("0.0000");
    }

    @Test
    void technicalFailureAtCommitRollsBackEverythingAndLeavesTheKeyReusable() throws Exception {
        Pair pair = pair();
        fund(pair.debit(), "800");
        long journals = count("journal_transactions");
        long entries = count("ledger_entries");
        long transfers = count("transfers");
        String key = UUID.randomUUID().toString();
        jdbc.execute("""
                CREATE FUNCTION test_http_fail_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'private ledger failure' USING ERRCODE = 'XX000'; END; $$
                """);
        jdbc.execute("CREATE CONSTRAINT TRIGGER test_http_fail_transfer AFTER INSERT ON transfers DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION test_http_fail_transfer()");
        try { error(transfer(pair, "500", key), 500, "INTERNAL_ERROR"); }
        finally {
            jdbc.execute("DROP TRIGGER test_http_fail_transfer ON transfers");
            jdbc.execute("DROP FUNCTION test_http_fail_transfer()");
        }
        assertThat(count("journal_transactions")).isEqualTo(journals);
        assertThat(count("ledger_entries")).isEqualTo(entries);
        assertThat(count("transfers")).isEqualTo(transfers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE idempotency_key = ?", Integer.class, key)).isZero();
        assertThat(balance(pair.source())).isEqualTo("800.0000");
        assertThat(transfer(pair, "500", key).statusCode()).isEqualTo(201);
    }

    @ParameterizedTest
    @ValueSource(strings = {"source-blocked", "destination-blocked", "source-closed", "destination-closed"})
    void inactiveAccountsCannotTransferButRemainQueryable(String state) throws Exception {
        Pair pair = pair();
        fund(pair.debit(), "800");
        UUID target = state.startsWith("source") ? pair.source() : pair.destination();
        tx().executeWithoutResult(status -> {
            Account account = accounts.findById(target).orElseThrow();
            if (state.endsWith("closed")) { account.close(account.getUpdatedAt().plusSeconds(1)); }
            else { account.block(account.getUpdatedAt().plusSeconds(1)); }
        });
        error(transfer(pair, "500", UUID.randomUUID().toString()), 409, state.endsWith("closed") ? "ACCOUNT_CLOSED" : "ACCOUNT_BLOCKED");
        assertThat(request("GET", "/api/v1/accounts/" + target, null).statusCode()).isEqualTo(200);
        assertThat(request("GET", "/api/v1/accounts/" + target + "/balance", null).statusCode()).isEqualTo(200);
    }

    @Test
    void economicErrorsAndMissingAccountsAreMappedWithoutHttpSpecificDomainChanges() throws Exception {
        Pair pair = pair();
        error(transfer(pair, "-1", UUID.randomUUID().toString()), 422, "INVALID_AMOUNT");
        error(transfer(pair, "500.001", UUID.randomUUID().toString()), 422, "INVALID_AMOUNT");
        error(request("POST", "/api/v1/transfers", transferBody(pair, "1", "ref").replace("MXN", "EUR"),
                "Idempotency-Key", UUID.randomUUID().toString()), 422, "INVALID_CURRENCY");
        error(request("POST", "/api/v1/transfers", transferBody(pair, "1", "ref").replace(pair.destination().toString(), pair.source().toString()),
                "Idempotency-Key", UUID.randomUUID().toString()), 422, "SAME_ACCOUNT");
        error(request("POST", "/api/v1/transfers", transferBody(pair, "1", "ref").replace(pair.source().toString(), UUID.randomUUID().toString()),
                "Idempotency-Key", UUID.randomUUID().toString()), 404, "ACCOUNT_NOT_FOUND");
        UUID usd = account(pair.customer(), "USD");
        error(request("POST", "/api/v1/transfers", transferBody(pair, "1", "ref").replace(pair.destination().toString(), usd.toString()),
                "Idempotency-Key", UUID.randomUUID().toString()), 422, "CURRENCY_MISMATCH");
    }

    @Test
    void numericAmountAndRepeatedJsonKeysAreRejectedByTheActualServer() throws Exception {
        Pair pair = pair();
        String body = transferBody(pair, "500.00", "ref");
        error(request("POST", "/api/v1/transfers", body.replace("\"500.00\"", "500.00"), "Idempotency-Key", "numeric-key"), 400, "MALFORMED_REQUEST");
        error(request("POST", "/api/v1/transfers", body.replace("\"amount\":", "\"amount\":\"1\",\"amount\":"), "Idempotency-Key", "duplicate-key"), 400, "MALFORMED_REQUEST");
        error(request("POST", "/api/v1/transfers", body.replace("\"reference\":", "\"balance\":\"1000\",\"reference\":"), "Idempotency-Key", "unknown-key"), 400, "MALFORMED_REQUEST");
        error(request("POST", "/api/v1/transfers", body, "Idempotency-Key", "one", "Idempotency-Key", "two"), 400, "INVALID_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE idempotency_key IN ('numeric-key','duplicate-key','unknown-key','one','two')", Integer.class)).isZero();
    }

    @Test
    void keysetHistoryAggregatesLinesAndTraversesEqualTimestampsWithoutDuplicates() throws Exception {
        Pair pair = pair();
        tx().executeWithoutResult(status -> {
            LedgerAccount a = ledgerAccounts.findById(pair.debit()).orElseThrow();
            LedgerAccount b = ledgerAccounts.findById(pair.credit()).orElseThrow();
            for (int index = 0; index < 5; index++) {
                JournalTransaction journal = JournalTransaction.draft("MXN", "same-time-history", AT);
                posting.post(journal, List.of(
                        LedgerEntry.create(journal, a, 1, EntrySide.DEBIT, new BigDecimal("2")),
                        LedgerEntry.create(journal, a, 2, EntrySide.DEBIT, new BigDecimal("3")),
                        LedgerEntry.create(journal, b, 3, EntrySide.CREDIT, new BigDecimal("5"))), AT);
            }
        });
        List<String> ids = new ArrayList<>();
        String path = "/api/v1/accounts/" + pair.source() + "/transactions?limit=2";
        String savedCursor = null;
        do {
            JsonNode page = body(request("GET", path, null));
            assertThat(page.path("items").size()).isBetween(1, 2);
            for (JsonNode item : page.path("items")) {
                ids.add(item.path("journalTransactionId").asString());
                assertThat(item.path("debitAmount").asString()).isEqualTo("5.0000");
                assertThat(item.path("creditAmount").asString()).isEqualTo("0.0000");
                assertThat(item.path("netAmount").asString()).isEqualTo("-5.0000");
                assertThat(item.path("transferId").isNull()).isTrue();
            }
            if (!page.path("hasMore").asBoolean()) {
                assertThat(page.path("nextCursor").isNull()).isTrue();
                break;
            }
            String cursor = page.path("nextCursor").asString();
            if (savedCursor == null) { savedCursor = cursor; }
            path = "/api/v1/accounts/" + pair.source() + "/transactions?limit=2&cursor=" + cursor;
        } while (ids.size() < 10);
        assertThat(ids).hasSize(5);
        assertThat(new HashSet<>(ids)).hasSize(5);
        assertThat(ids).isSortedAccordingTo(java.util.Comparator.reverseOrder());
        JsonNode maximumPage = body(request("GET", "/api/v1/accounts/" + pair.source() + "/transactions?limit=100", null));
        assertThat(maximumPage.path("items").size()).isEqualTo(5);
        assertThat(maximumPage.has("totalCount")).isFalse();
        error(request("GET", "/api/v1/accounts/" + pair.destination() + "/transactions?cursor=" + savedCursor, null), 400, "INVALID_CURSOR");
        error(request("GET", "/api/v1/accounts/" + pair.source() + "/transactions?cursor=invalid", null), 400, "INVALID_CURSOR");
        // Validate the actual aggregate query with PostgreSQL's planner using existing indexes.
        List<String> plan = jdbc.queryForList("""
                EXPLAIN SELECT j.id, j.posted_at, sum(e.amount)
                FROM ledger_entries e JOIN journal_transactions j ON j.id = e.journal_transaction_id
                LEFT JOIN transfers t ON t.journal_transaction_id = j.id
                WHERE e.ledger_account_id = ? AND j.status = 'POSTED'
                GROUP BY j.id,t.id ORDER BY j.posted_at DESC,j.id DESC LIMIT 3
                """, String.class, pair.debit());
        assertThat(String.join("\n", plan)).contains("Limit").contains("ledger_entries");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "101", "-1", "abc", "1.5", "999999999999999999"})
    void paginationLimitsAreCheckedWithoutUnboundedReads(String limit) throws Exception {
        Pair pair = pair();
        error(request("GET", "/api/v1/accounts/" + pair.source() + "/transactions?limit=" + limit, null), 400, "INVALID_LIMIT");
    }

    @Test
    void historyDefaultLimitIs20AndUpperBoundExcludesNewerPostingsBetweenPages() throws Exception {
        Pair pair = pair();
        for (int index = 0; index < 21; index++) { fund(pair.debit(), "1"); }
        JsonNode first = body(request("GET", "/api/v1/accounts/" + pair.source() + "/transactions", null));
        assertThat(first.path("items").size()).isEqualTo(20);
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        String cursor = first.path("nextCursor").asString();
        tx().executeWithoutResult(status -> {
            LedgerAccount debit = ledgerAccounts.findById(pair.debit()).orElseThrow();
            LedgerAccount credit = ledgerAccounts.findById(pair.credit()).orElseThrow();
            JournalTransaction newer = JournalTransaction.draft("MXN", "newer-than-cursor", AT.plusSeconds(10));
            posting.post(newer, List.of(LedgerEntry.create(newer, debit, 1, EntrySide.DEBIT, BigDecimal.ONE),
                    LedgerEntry.create(newer, credit, 2, EntrySide.CREDIT, BigDecimal.ONE)), AT.plusSeconds(10));
        });
        JsonNode last = body(request("GET", "/api/v1/accounts/" + pair.source() + "/transactions?cursor=" + cursor, null));
        assertThat(last.path("items").size()).isEqualTo(1);
        assertThat(last.path("hasMore").asBoolean()).isFalse();
        assertThat(last.toString()).doesNotContain("newer-than-cursor");
    }

    @Test
    void concurrentHttpDuplicatesOnlyMoveMoneyOnce() throws Exception {
        Pair pair = pair();
        fund(pair.debit(), "800");
        String key = UUID.randomUUID().toString();
        List<HttpResponse<String>> results = race(() -> transfer(pair, "500", key), () -> transfer(pair, "500.00", key));
        assertThat(results).allSatisfy(response -> assertThat(response.statusCode()).isEqualTo(201));
        assertThat(body(results.getFirst())).isEqualTo(body(results.getLast()));
        assertThat(results.getFirst().headers().firstValue("Location")).isEqualTo(results.getLast().headers().firstValue("Location"));
        assertThat(balance(pair.source())).isEqualTo("300.0000");
    }

    @Test
    void idempotencyKeysRemainCaseSensitiveAtTheHttpBoundary() throws Exception {
        Pair pair = pair();
        fund(pair.debit(), "1000");
        String key = UUID.randomUUID().toString();
        var first = transfer(pair, "500", "lower-" + key);
        var second = transfer(pair, "500", "LOWER-" + key);
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(second.statusCode()).isEqualTo(201);
        assertThat(body(first).path("id")).isNotEqualTo(body(second).path("id"));
        assertThat(balance(pair.source())).isEqualTo("0.0000");
    }

    @Test
    void concurrentHttpRequestsCompetingFor800HaveOneSuccessAndOneCommittedRejection() throws Exception {
        Pair pair = pair();
        fund(pair.debit(), "800");
        String a = UUID.randomUUID().toString();
        String b = UUID.randomUUID().toString();
        List<HttpResponse<String>> results = race(() -> transfer(pair, "500", a), () -> transfer(pair, "500", b));
        assertThat(results).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        error(results.stream().filter(response -> response.statusCode() == 409).findFirst().orElseThrow(), 409, "INSUFFICIENT_FUNDS");
        assertThat(balance(pair.source())).isEqualTo("300.0000");
        assertThat(balance(pair.destination())).isEqualTo("500.0000");
        assertThat(jdbc.queryForList("SELECT status FROM transfer_idempotency_records WHERE idempotency_key IN (?,?)", String.class, a, b))
                .containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
    }

    private List<HttpResponse<String>> race(Callable<HttpResponse<String>> first, Callable<HttpResponse<String>> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<HttpResponse<String>> a = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return first.call(); });
            Future<HttpResponse<String>> b = executor.submit(() -> { barrier.await(5, TimeUnit.SECONDS); return second.call(); });
            return List.of(a.get(25, TimeUnit.SECONDS), b.get(25, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void openApiUsesOnlyPublicPathsAndDescribesMoneyErrorsAndRequiredHeader() throws Exception {
        var response = request("GET", "/v3/api-docs", null);
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode doc = body(response);
        java.nio.file.Files.writeString(java.nio.file.Path.of("target", "api-openapi.json"), response.body());
        assertThat(doc.path("paths").size()).isEqualTo(8);
        assertThat(doc.path("paths").has("/api/v1/accounts/{id}/transactions")).isTrue();
        assertThat(doc.path("paths").has("/api/v1/ledger-entry")).isFalse();
        JsonNode schemas = doc.path("components").path("schemas");
        assertThat(schemas.has("ApiError")).isTrue();
        assertThat(schemas.has("LedgerEntry")).isFalse();
        assertThat(schemas.has("TransferIdempotencyRecord")).isFalse();
        assertThat(schemas.path("CreateTransferRequest").path("properties").path("amount").path("type").asString()).isEqualTo("string");
        JsonNode operation = doc.path("paths").path("/api/v1/transfers").path("post");
        JsonNode key = null;
        for (JsonNode parameter : operation.path("parameters")) {
            if (parameter.path("name").asString().equals("Idempotency-Key")) { key = parameter; }
        }
        assertThat(key).isNotNull();
        assertThat(key.path("required").asBoolean()).isTrue();
        assertThat(key.path("schema").path("maxLength").asInt()).isEqualTo(128);
        assertThat(operation.path("description").asString()).contains("201", "replay", "same key", "Reference");
        assertThat(operation.path("responses").has("409")).isTrue();
        assertThat(operation.path("responses").path("409").path("content").path("application/json").path("schema").path("$ref").asString())
                .isEqualTo("#/components/schemas/ApiError");
        // Swagger UI redirect and its served page both come from the actual runtime.
        var ui = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/swagger-ui/index.html")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(ui.statusCode()).isEqualTo(200);
        assertThat(ui.body()).contains("Swagger UI");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
        assertThat(flyway.info().pending()).isEmpty();
    }

    @AfterEach
    void noHttpRequestLeavesACommittedReservation() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE status = 'RESERVED'", Integer.class)).isZero();
    }

    @AfterAll
    void dropOnlyTheGeneratedHttpTestSchema() {
        if (!SCHEMA.matches("api_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe schema name"); }
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
}
