package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.Customer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LedgerIntegrationTests {

    private static final Instant AT = Instant.parse("2026-10-07T12:00:00.123456Z");
    private static final String SCHEMA = "ledger_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired
    private LedgerPostingService posting;

    @Autowired
    private LedgerBalanceService balances;

    @Autowired
    private LedgerAccountRepository ledgerAccounts;

    @Autowired
    private JournalTransactionRepository journals;

    @Autowired
    private LedgerEntryRepository entries;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @PersistenceContext
    private EntityManager entityManager;

    private TransactionTemplate transactions() {
        return new TransactionTemplate(transactionManager);
    }

    private record Fixture(Account bankA, LedgerAccount a, LedgerAccount b, LedgerAccount cash) {
    }

    private Fixture fixture() {
        return transactions().execute(status -> {
            Customer customer = Customer.create("Ana", "López", null, AT);
            Account bankA = Account.create(customer, AccountType.CHECKING, "MXN", AT);
            Account bankB = Account.create(customer, AccountType.SAVINGS, "MXN", AT);
            entityManager.persist(customer);
            entityManager.persist(bankA);
            entityManager.persist(bankB);
            LedgerAccount a = LedgerAccount.forAccount(bankA, AT);
            LedgerAccount b = LedgerAccount.forAccount(bankB, AT);
            LedgerAccount cash = LedgerAccount.internal("CASH_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    LedgerAccountCategory.ASSET, "MXN", AT);
            entityManager.persist(a);
            entityManager.persist(b);
            entityManager.persist(cash);
            entityManager.flush();
            return new Fixture(bankA, a, b, cash);
        });
    }

    private JournalTransaction post(LedgerAccount debit, LedgerAccount credit, String amount) {
        JournalTransaction journal = JournalTransaction.draft("MXN", "integration-fixture", AT);
        return posting.post(journal, List.of(
                LedgerEntry.create(journal, debit, 1, EntrySide.DEBIT, new BigDecimal(amount)),
                LedgerEntry.create(journal, credit, 2, EntrySide.CREDIT, new BigDecimal(amount))), AT.plusSeconds(1));
    }

    @Test
    void conceptualTransferOf500PreservesTotalAndUsesRealAccountRelationships() {
        Fixture f = fixture();
        // A balanced fixture journal establishes the opening amounts; this is not a deposit use case.
        post(f.cash(), f.a(), "1000");
        BigDecimal before = balances.getBalance(f.a().getId()).add(balances.getBalance(f.b().getId()));
        JournalTransaction transfer = post(f.a(), f.b(), "500");
        assertThat(balances.getBalance(f.a().getId())).isEqualByComparingTo("500");
        assertThat(balances.getBalance(f.b().getId())).isEqualByComparingTo("500");
        assertThat(balances.getBalance(f.cash().getId())).isEqualByComparingTo("1000");
        assertThat(balances.getBalance(f.a().getId()).add(balances.getBalance(f.b().getId())))
                .isEqualByComparingTo(before);
        transactions().executeWithoutResult(status -> {
            entityManager.clear();
            JournalTransaction reloaded = journals.findById(transfer.getId()).orElseThrow();
            assertThat(reloaded).isNotSameAs(transfer);
            assertThat(reloaded.getStatus()).isEqualTo(JournalStatus.POSTED);
            assertThat(reloaded.getPostedAt()).isEqualTo(AT.plusSeconds(1));
            List<LedgerEntry> lines = entries.findAllByJournalTransactionIdOrderByLineNumberAsc(transfer.getId());
            assertThat(lines).hasSize(2);
            assertThat(lines).extracting(LedgerEntry::getSide).containsExactly(EntrySide.DEBIT, EntrySide.CREDIT);
            assertThat(lines).allSatisfy(line -> assertThat(line.getAmount()).isEqualTo(new BigDecimal("500.0000")));
            assertThat(Hibernate.isInitialized(lines.get(0).getLedgerAccount())).isFalse();
            LedgerAccount ledger = ledgerAccounts.findByAccountId(f.bankA().getId()).orElseThrow();
            assertThat(Hibernate.isInitialized(ledger.getAccount())).isFalse();
            assertThat(ledger.getAccount().getId()).isEqualTo(f.bankA().getId());
            assertThat(ledger.getAccount().getCurrencyCode()).isEqualTo("MXN");
            assertThat(ledgerAccounts.findById(f.cash().getId()).orElseThrow().getAccount()).isNull();
        });
    }

    @Test
    void accountWithoutEntriesHasZeroBalanceAndUnknownAccountIsRejected() {
        Fixture f = fixture();
        assertThat(balances.getBalance(f.a().getId())).isEqualTo(new BigDecimal("0.0000"));
        assertThat(balances.getBalance(f.cash().getId())).isEqualTo(new BigDecimal("0.0000"));
        assertThatIllegalArgumentException().isThrownBy(() -> balances.getBalance(UUID.randomUUID()));
    }

    @Test
    void balanceIgnoresDraftEntriesWithinTheBuildingTransaction() {
        Fixture f = fixture();
        transactions().executeWithoutResult(status -> {
            UUID journal = insertDraft();
            insertEntry(journal, f.cash().getId(), 1, "DEBIT", "10", "MXN");
            insertEntry(journal, f.a().getId(), 2, "CREDIT", "10", "MXN");
            assertThat(balances.getBalance(f.a().getId())).isEqualByComparingTo("0");
            postSql(journal);
        });
        assertThat(balances.getBalance(f.a().getId())).isEqualByComparingTo("10");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void actualCommitRejectsDraftWithOrWithoutBalancedEntries(boolean withEntries) {
        Fixture f = fixture();
        UUID journal = UUID.randomUUID();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            insertDraft(journal);
            if (withEntries) {
                insertEntry(journal, f.a().getId(), 1, "DEBIT", "500", "MXN");
                insertEntry(journal, f.b().getId(), 2, "CREDIT", "500", "MXN");
            }
            // Callback returns normally; TransactionTemplate really attempts COMMIT.
        }), "23514");
        assertAbsent(journal);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unbalanced", "oneLine", "oneAccount", "debitsOnly", "creditsOnly", "noLines"})
    void actualCommitRejectsInvalidPostedJournal(String scenario) {
        Fixture f = fixture();
        UUID journal = UUID.randomUUID();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            insertDraft(journal);
            if (!scenario.equals("noLines")) {
                insertEntry(journal, f.a().getId(), 1, scenario.equals("creditsOnly") ? "CREDIT" : "DEBIT", "500", "MXN");
                if (!scenario.equals("oneLine")) {
                    insertEntry(journal, scenario.equals("oneAccount") ? f.a().getId() : f.b().getId(), 2,
                            scenario.equals("debitsOnly") ? "DEBIT" : "CREDIT",
                            scenario.equals("unbalanced") ? "499.9999" : "500", "MXN");
                }
            }
            postSql(journal);
            // Reaches COMMIT, where ct_journal_complete rejects the complete final state.
        }), "23514");
        assertAbsent(journal);
    }

    @ParameterizedTest
    @MethodSource("invalidEntries")
    void directSqlCannotBypassEntryConstraints(String side, String amount, String currency, int line, String state) {
        Fixture f = fixture();
        UUID journal = UUID.randomUUID();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            insertDraft(journal);
            insertEntry(journal, f.a().getId(), line, side, amount, currency);
        }), state);
        assertAbsent(journal);
    }

    static Stream<Arguments> invalidEntries() {
        return Stream.of(
                Arguments.of("DEBIT", "0", "MXN", 1, "23514"),
                Arguments.of("DEBIT", "-1", "MXN", 1, "23514"),
                Arguments.of("DEBIT", "NaN", "MXN", 1, "23514"),
                Arguments.of("DEBIT", null, "MXN", 1, "23502"),
                Arguments.of("DEBIT", "1000000000000000", "MXN", 1, "22003"),
                Arguments.of("UNKNOWN", "1", "MXN", 1, "23514"),
                Arguments.of(null, "1", "MXN", 1, "23502"),
                Arguments.of("DEBIT", "1", "USD", 1, "23503"),
                Arguments.of("DEBIT", "1", "MXN", 0, "23514"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"updateEntry", "deleteEntry", "updateJournal", "deleteJournal", "appendEntry",
            "truncateEntries", "truncateJournals", "truncateAccounts", "updateLedgerAccount"})
    void postedHistoryRejectsDirectSqlMutation(String mutation) {
        Fixture f = fixture();
        JournalTransaction journal = post(f.cash(), f.a(), "500");
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            switch (mutation) {
                case "updateEntry" -> jdbc.update("update ledger_entries set amount = 1 where journal_transaction_id = ?", journal.getId());
                case "deleteEntry" -> jdbc.update("delete from ledger_entries where journal_transaction_id = ?", journal.getId());
                case "updateJournal" -> jdbc.update("update journal_transactions set reference = 'changed' where id = ?", journal.getId());
                case "deleteJournal" -> jdbc.update("delete from journal_transactions where id = ?", journal.getId());
                case "appendEntry" -> insertEntry(journal.getId(), f.a().getId(), 3, "CREDIT", "1", "MXN");
                case "truncateEntries" -> jdbc.execute("truncate ledger_entries");
                case "truncateJournals" -> jdbc.execute("truncate journal_transactions cascade");
                case "truncateAccounts" -> jdbc.execute("truncate ledger_accounts cascade");
                case "updateLedgerAccount" -> jdbc.update("update ledger_accounts set category = 'LIABILITY' where id = ?", f.cash().getId());
                default -> throw new IllegalArgumentException("Unknown test scenario");
            }
        }), "23514");
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where journal_transaction_id = ?",
                Integer.class, journal.getId())).isEqualTo(2);
        assertThat(balances.getBalance(f.a().getId())).isEqualByComparingTo("500");
        assertThat(journals.findById(journal.getId()).orElseThrow().getReference()).isEqualTo("integration-fixture");
    }

    @Test
    void failedDeferredCommitRollsBackEvenAnOtherwiseValidServicePosting() {
        Fixture f = fixture();
        JournalTransaction valid = JournalTransaction.draft("MXN", null, AT);
        UUID unfinished = UUID.randomUUID();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            posting.post(valid, List.of(
                    LedgerEntry.create(valid, f.cash(), 1, EntrySide.DEBIT, new BigDecimal("500")),
                    LedgerEntry.create(valid, f.a(), 2, EntrySide.CREDIT, new BigDecimal("500"))), AT);
            insertDraft(unfinished);
        }), "23514");
        assertAbsent(valid.getId());
        assertAbsent(unfinished);
        assertThat(balances.getBalance(f.a().getId())).isEqualByComparingTo("0");
    }

    @Test
    void serviceRejectsUnbalancedProposalWithoutPersistingAnyHistory() {
        Fixture f = fixture();
        JournalTransaction journal = JournalTransaction.draft("MXN", null, AT);
        assertThatIllegalArgumentException().isThrownBy(() -> posting.post(journal, List.of(
                LedgerEntry.create(journal, f.cash(), 1, EntrySide.DEBIT, new BigDecimal("500")),
                LedgerEntry.create(journal, f.a(), 2, EntrySide.CREDIT, new BigDecimal("499"))), AT));
        assertAbsent(journal.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"none", "both", "customerAsset", "wrongCurrency", "invalidCategory", "invalidSystemCode"})
    void ledgerAccountIdentityAndCurrencyAreProtectedInSql(String scenario) {
        Fixture f = fixture();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            UUID accountId = f.bankA().getId();
            if (scenario.equals("wrongCurrency")) {
                // Use an account with no ledger binding so uniqueness cannot mask the currency FK.
                Customer customer = Customer.create("Luis", "Pérez", null, AT);
                Account unbound = Account.create(customer, AccountType.CHECKING, "MXN", AT);
                entityManager.persist(customer);
                entityManager.persist(unbound);
                entityManager.flush();
                accountId = unbound.getId();
            }
            UUID account = scenario.equals("none") || scenario.equals("invalidCategory") || scenario.equals("invalidSystemCode")
                    ? null : accountId;
            String code = scenario.equals("both") || scenario.equals("invalidCategory") ? "OTHER"
                    : scenario.equals("invalidSystemCode") ? "bad code" : null;
            jdbc.update("insert into ledger_accounts (id, account_id, system_code, category, currency_code, created_at) "
                            + "values (?, ?, ?, ?, ?, ?)", UUID.randomUUID(), account, code,
                    scenario.equals("customerAsset") ? "ASSET" : scenario.equals("invalidCategory") ? "UNKNOWN" : "LIABILITY",
                    scenario.equals("wrongCurrency") ? "USD" : "MXN", AT.atOffset(java.time.ZoneOffset.UTC));
        }), scenario.equals("wrongCurrency") ? "23503" : "23514");
    }

    @Test
    void duplicateCustomerLedgerAccountAndDuplicateJournalLinesAreRejected() {
        Fixture f = fixture();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            entityManager.persist(LedgerAccount.forAccount(f.bankA(), AT));
            entityManager.flush();
        }), "23505");
        UUID journal = UUID.randomUUID();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            insertDraft(journal);
            insertEntry(journal, f.a().getId(), 1, "DEBIT", "1", "MXN");
            insertEntry(journal, f.b().getId(), 1, "CREDIT", "1", "MXN");
        }), "23505");
        assertAbsent(journal);
    }

    @Test
    void journalCannotBeInsertedAsPostedOrHaveItsDraftMetadataEdited() {
        assertSqlState(() -> transactions().executeWithoutResult(status -> jdbc.update(
                "insert into journal_transactions (id, currency_code, status, created_at, posted_at) "
                        + "values (?, 'MXN', 'POSTED', ?, ?)", UUID.randomUUID(), AT.atOffset(java.time.ZoneOffset.UTC),
                AT.atOffset(java.time.ZoneOffset.UTC))), "23514");
        UUID journal = UUID.randomUUID();
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            insertDraft(journal);
            jdbc.update("update journal_transactions set reference = 'changed' where id = ?", journal);
        }), "23514");
        assertAbsent(journal);
    }

    @Test
    void guardsDoNotTrustTheCallersSearchPath() {
        Fixture f = fixture();
        JournalTransaction posted = post(f.cash(), f.a(), "1");
        assertSqlState(() -> transactions().executeWithoutResult(status -> {
            jdbc.execute("create temporary table journal_transactions (id uuid, status varchar(20))");
            jdbc.update("insert into pg_temp.journal_transactions values (?, 'DRAFT')", posted.getId());
            jdbc.update("insert into " + SCHEMA + ".ledger_entries "
                            + "(id, journal_transaction_id, ledger_account_id, line_number, side, amount, currency_code) "
                            + "values (?, ?, ?, 3, 'CREDIT', 1, 'MXN')",
                    UUID.randomUUID(), posted.getId(), f.a().getId());
        }), "23514");
    }

    private UUID insertDraft() {
        UUID id = UUID.randomUUID();
        insertDraft(id);
        return id;
    }

    private void insertDraft(UUID id) {
        jdbc.update("insert into journal_transactions (id, currency_code, status, created_at) values (?, 'MXN', 'DRAFT', ?)",
                id, AT.atOffset(java.time.ZoneOffset.UTC));
    }

    private void insertEntry(UUID journal, UUID account, int line, String side, String amount, String currency) {
        jdbc.update("insert into ledger_entries (id, journal_transaction_id, ledger_account_id, line_number, side, amount, currency_code) "
                        + "values (?, ?, ?, ?, ?, cast(? as numeric), ?)",
                UUID.randomUUID(), journal, account, line, side, amount, currency);
    }

    private void postSql(UUID journal) {
        jdbc.update("update journal_transactions set status = 'POSTED', posted_at = ? where id = ?",
                AT.plusSeconds(1).atOffset(java.time.ZoneOffset.UTC), journal);
    }

    private void assertAbsent(UUID journal) {
        assertThat(jdbc.queryForObject("select count(*) from journal_transactions where id = ?", Integer.class, journal)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from ledger_entries where journal_transaction_id = ?", Integer.class, journal)).isZero();
    }

    private static void assertSqlState(Runnable operation, String expected) {
        assertThatThrownBy(operation::run).satisfies(exception -> {
            Throwable cause = exception;
            while (cause != null && !(cause instanceof SQLException)) {
                cause = cause.getCause();
            }
            assertThat(cause).as("PostgreSQL exception in the cause chain").isInstanceOf(SQLException.class);
            assertThat(((SQLException) cause).getSQLState()).isEqualTo(expected);
        });
    }

    @AfterAll
    void removeOnlyThisRunsIsolatedSchema() {
        if (!SCHEMA.matches("ledger_test_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing to remove an unexpected schema");
        }
        // Administrative teardown of this generated test schema, never public or production data.
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
}
