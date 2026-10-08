package io.github.alejandro117b.fincore.dev.demo;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

import io.github.alejandro117b.fincore.account.AccountRepository;
import io.github.alejandro117b.fincore.account.AccountStatus;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.CustomerRepository;
import io.github.alejandro117b.fincore.ledger.LedgerAccount;
import io.github.alejandro117b.fincore.ledger.LedgerAccountCategory;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerBalanceService;
import io.github.alejandro117b.fincore.transfer.TransferCommand;
import io.github.alejandro117b.fincore.transfer.TransferService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "fincore.demo.seed.enabled=true")
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
@Timeout(45)
class StarterDemoSeederIntegrationTests {
    private static final String SCHEMA = "demo_test_" + UUID.randomUUID().toString().replace("-", "");
    private StarterDemoSeeder.SeedIds startupIds;

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired private StarterDemoSeeder seeder;
    @Autowired @Qualifier("demoSeedRunner") private ApplicationRunner runner;
    @Autowired private CustomerRepository customers;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerAccountRepository ledgers;
    @Autowired private LedgerBalanceService balances;
    @Autowired private TransferService transfers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private Flyway flyway;
    @PersistenceContext private EntityManager em;

    @BeforeEach
    void recreateOnlyTheGeneratedSchema() {
        if (startupIds == null) {
            // The real ApplicationRunner must already have committed the scenario.
            assertThat(count("demo_seed_runs")).isEqualTo(1);
            assertThat(count("customers")).isEqualTo(2);
            startupIds = seeder.seed();
        }
        dropOnlyTheGeneratedSchema();
        flyway.migrate();
    }

    @Test
    void applicationRunnerSeedsOnStartupAndDevMigrationIsApplied() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("fincore_dev");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        assertThat(startupIds.alejandroCustomerId()).isNotNull();
        assertThat(startupIds.fernandoCustomerId()).isNotEqualTo(startupIds.alejandroCustomerId());
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/dev-migration");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE script = 'R__create_demo_seed_runs.sql' AND success",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void firstExecutionCreatesServiceContractsAndRealBalancedPostedJournal() {
        var ids = seeder.seed();
        var alejandro = customers.findById(ids.alejandroCustomerId()).orElseThrow();
        var fernando = customers.findById(ids.fernandoCustomerId()).orElseThrow();
        assertThat(alejandro.getFirstName()).isEqualTo("Alejandro");
        assertThat(alejandro.getLastName()).isEqualTo("Demo");
        assertThat(fernando.getFirstName()).isEqualTo("Fernando");
        assertThat(fernando.getLastName()).isEqualTo("Demo");
        assertThat(accounts.findById(ids.alejandroAccountId()).orElseThrow().getType()).isEqualTo(AccountType.CHECKING);
        assertThat(accounts.findById(ids.fernandoAccountId()).orElseThrow().getType()).isEqualTo(AccountType.SAVINGS);
        assertThat(accounts.findById(ids.alejandroAccountId()).orElseThrow().getStatus()).isEqualTo(AccountStatus.ACTIVE);
        assertScenarioCounts(1, 2);
        assertThat(jdbc.queryForObject("SELECT status FROM journal_transactions WHERE id = ?", String.class,
                ids.fundingJournalId())).isEqualTo("POSTED");
        assertThat(jdbc.queryForList("""
                SELECT a.system_code, a.category, a.currency_code, e.side, e.amount
                FROM ledger_entries e JOIN ledger_accounts a ON a.id = e.ledger_account_id
                WHERE e.journal_transaction_id = ? ORDER BY e.line_number
                """, ids.fundingJournalId())).satisfies(lines -> {
            assertThat(lines).hasSize(2);
            assertThat(lines.getFirst()).containsEntry("system_code", "DEMO_CASH_MXN")
                    .containsEntry("category", "ASSET").containsEntry("currency_code", "MXN")
                    .containsEntry("side", "DEBIT").containsEntry("amount", new BigDecimal("2000.0000"));
            assertThat(lines.get(1)).containsEntry("system_code", null).containsEntry("category", "LIABILITY")
                    .containsEntry("currency_code", "MXN").containsEntry("side", "CREDIT")
                    .containsEntry("amount", new BigDecimal("2000.0000"));
        });
        var row = jdbc.queryForMap("SELECT * FROM demo_seed_runs WHERE scenario_key = ?", StarterDemoSeeder.SCENARIO_KEY);
        assertThat(row).containsEntry("alejandro_customer_id", ids.alejandroCustomerId())
                .containsEntry("fernando_customer_id", ids.fernandoCustomerId())
                .containsEntry("alejandro_account_id", ids.alejandroAccountId())
                .containsEntry("fernando_account_id", ids.fernandoAccountId())
                .containsEntry("funding_journal_id", ids.fundingJournalId());
        assertThat(row.get("definition_hash").toString()).matches("[0-9a-f]{64}");
        assertThat(row.get("created_at")).isNotNull();
    }

    @Test
    void balancesComeFromTheLedgerIncludingZeroAndCashCounterpart() {
        var ids = seeder.seed();
        assertBalance(ids.alejandroAccountId(), "2000.0000");
        assertBalance(ids.fernandoAccountId(), "0.0000");
        UUID cash = jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE system_code = 'DEMO_CASH_MXN'", UUID.class);
        assertThat(balances.getBalance(cash)).isEqualByComparingTo("2000.0000");
    }

    @Test
    void secondExecutionReturnsStoredIdsWithoutAnyNewDataOrMoney() {
        var first = seeder.seed();
        var createdAt = jdbc.queryForObject("SELECT created_at FROM demo_seed_runs", Timestamp.class);
        assertThat(seeder.seed()).isEqualTo(first);
        assertScenarioCounts(1, 2);
        assertBalance(first.alejandroAccountId(), "2000.0000");
        assertBalance(first.fernandoAccountId(), "0.0000");
        assertThat(jdbc.queryForObject("SELECT created_at FROM demo_seed_runs", Timestamp.class)).isEqualTo(createdAt);
    }

    @Test
    void restartingAfterSpendingNeverReplenishesFunds() {
        var first = seeder.seed();
        transfers.execute(new TransferCommand(first.alejandroAccountId(), first.fernandoAccountId(),
                new BigDecimal("500.0000"), "MXN", "demo-spend", "demo transfer"));
        assertThat(seeder.seed()).isEqualTo(first);
        assertScenarioCounts(2, 4);
        assertBalance(first.alejandroAccountId(), "1500.0000");
        assertBalance(first.fernandoAccountId(), "500.0000");
    }

    @Test
    void twoExecutionsWaitOnThePostgresAdvisoryLockAndCreateOnlyOneScenario() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(3)) {
            var blocker = executor.submit(() -> new TransactionTemplate(manager).execute(status -> {
                jdbc.query("SELECT pg_advisory_xact_lock(?, ?)", rs -> { rs.next(); return null; },
                        StarterDemoSeeder.LOCK_NAMESPACE, StarterDemoSeeder.LOCK_KEY);
                locked.countDown();
                await(release);
                return null;
            }));
            try {
                await(locked);
                var first = executor.submit(seeder::seed);
                var second = executor.submit(seeder::seed);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                                + "AND classid = ? AND objid = ? AND objsubid = 2 AND NOT granted", Integer.class,
                        StarterDemoSeeder.LOCK_NAMESPACE, StarterDemoSeeder.LOCK_KEY) < 2) {
                    if (System.nanoTime() >= deadline) {
                        throw new AssertionError("Both seed transactions must wait for the advisory lock");
                    }
                    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
                }
                release.countDown();
                blocker.get(10, TimeUnit.SECONDS);
                var ids = first.get(10, TimeUnit.SECONDS);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(ids);
                assertScenarioCounts(1, 2);
                assertBalance(ids.alejandroAccountId(), "2000.0000");
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void failureAfterPostingRollsBackEverythingAndNextExecutionCanRetry() {
        installFailure(false);
        try {
            assertThatThrownBy(seeder::seed).hasStackTraceContaining("forced demo failure");
            assertEmptyScenario();
        } finally {
            removeFailure();
        }
        var ids = seeder.seed();
        assertScenarioCounts(1, 2);
        assertBalance(ids.alejandroAccountId(), "2000.0000");
    }

    @Test
    void deferredCommitFailureDoesNotLogSuccessOrLeaveFunds(CapturedOutput output) {
        installFailure(true);
        try {
            assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments(new String[0])))
                    .hasStackTraceContaining("forced demo failure");
            assertEmptyScenario();
            assertThat(output.getAll()).doesNotContain("Demo scenario starter-mxn-v1 ready.");
        } finally {
            removeFailure();
        }
        assertThat(seeder.seed().fundingJournalId()).isNotNull();
    }

    @Test
    void differentDefinitionHashFailsWithoutChangingDataOrFunds() {
        var ids = seeder.seed();
        jdbc.update("UPDATE demo_seed_runs SET definition_hash = ? WHERE scenario_key = ?",
                "0".repeat(64), StarterDemoSeeder.SCENARIO_KEY);
        assertThatThrownBy(seeder::seed).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("starter-mxn-v1").hasMessageContaining("definition_hash mismatch");
        assertScenarioCounts(1, 2);
        assertBalance(ids.alejandroAccountId(), "2000.0000");
        assertBalance(ids.fernandoAccountId(), "0.0000");
        assertThat(jdbc.queryForObject("SELECT definition_hash FROM demo_seed_runs", String.class)).isEqualTo("0".repeat(64));
    }

    @ParameterizedTest
    @MethodSource("incompatibleCash")
    void incompatibleInternalCounterpartFailsAndRollsBackNewCustomers(LedgerAccountCategory category, String currency) {
        UUID cash = createCash(category, currency);
        assertThatThrownBy(seeder::seed).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DEMO_CASH_MXN must be an internal ASSET account in MXN");
        assertThat(count("demo_seed_runs")).isZero();
        assertThat(count("customers")).isZero();
        assertThat(count("accounts")).isZero();
        assertThat(count("ledger_accounts")).isEqualTo(1);
        assertThat(count("journal_transactions")).isZero();
        assertThat(count("ledger_entries")).isZero();
        assertThat(balances.getBalance(cash)).isZero();
    }

    static Stream<Arguments> incompatibleCash() {
        return Stream.of(Arguments.of(LedgerAccountCategory.LIABILITY, "MXN"),
                Arguments.of(LedgerAccountCategory.ASSET, "USD"));
    }

    @Test
    void compatibleCashAccountIsReused() {
        UUID cash = createCash(LedgerAccountCategory.ASSET, "MXN");
        seeder.seed();
        assertScenarioCounts(1, 2);
        assertThat(jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE system_code = 'DEMO_CASH_MXN'", UUID.class))
                .isEqualTo(cash);
        assertThat(balances.getBalance(cash)).isEqualByComparingTo("2000.0000");
    }

    @Test
    void runnerLogsThePersistedIdsAndExpectedOpeningAmount(CapturedOutput output) throws Exception {
        runner.run(new DefaultApplicationArguments(new String[0]));
        var ids = seeder.seed();
        assertThat(output.getAll()).contains("Demo scenario starter-mxn-v1 ready.",
                "Alejandro Demo: Customer ID=" + ids.alejandroCustomerId(), "Account ID=" + ids.alejandroAccountId(),
                "expected initial balance=2000.0000 MXN", "Fernando Demo: Customer ID=" + ids.fernandoCustomerId(),
                "Account ID=" + ids.fernandoAccountId(), "Funding Journal ID=" + ids.fundingJournalId());
    }

    private UUID createCash(LedgerAccountCategory category, String currency) {
        return new TransactionTemplate(manager).execute(status -> {
            var cash = LedgerAccount.internal("DEMO_CASH_MXN", category, currency, Instant.now());
            em.persist(cash);
            em.flush();
            return cash.getId();
        });
    }

    private void installFailure(boolean deferred) {
        jdbc.execute("""
                CREATE FUNCTION demo_seed_force_failure() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'forced demo failure' USING ERRCODE = 'XX000'; END; $$
                """);
        jdbc.execute(deferred
                ? "CREATE CONSTRAINT TRIGGER demo_seed_failure AFTER UPDATE ON demo_seed_runs "
                    + "DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION demo_seed_force_failure()"
                : "CREATE TRIGGER demo_seed_failure BEFORE UPDATE ON demo_seed_runs "
                    + "FOR EACH ROW EXECUTE FUNCTION demo_seed_force_failure()");
    }

    private void removeFailure() {
        jdbc.execute("DROP TRIGGER demo_seed_failure ON demo_seed_runs");
        jdbc.execute("DROP FUNCTION demo_seed_force_failure()");
    }

    private void assertScenarioCounts(int journals, int entries) {
        assertThat(count("demo_seed_runs")).isEqualTo(1);
        assertThat(count("customers")).isEqualTo(2);
        assertThat(count("accounts")).isEqualTo(2);
        assertThat(count("ledger_accounts")).isEqualTo(3);
        assertThat(count("journal_transactions")).isEqualTo(journals);
        assertThat(count("ledger_entries")).isEqualTo(entries);
    }

    private void assertEmptyScenario() {
        for (String table : List.of("demo_seed_runs", "customers", "accounts", "ledger_accounts",
                "journal_transactions", "ledger_entries")) {
            assertThat(count(table)).as(table).isZero();
        }
    }

    private long count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class); }

    private void assertBalance(UUID accountId, String expected) {
        UUID ledgerId = ledgers.findByAccountId(accountId).orElseThrow().getId();
        assertThat(balances.getBalance(ledgerId)).isEqualByComparingTo(expected);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(8, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    @AfterAll
    void dropOnlyTheGeneratedSchema() {
        if (!SCHEMA.matches("demo_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe test schema name"); }
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
}
