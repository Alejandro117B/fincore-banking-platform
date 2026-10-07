package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import io.github.alejandro117b.fincore.account.*;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.ledger.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.hibernate.Hibernate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(TransferIntegrationTests.DeterministicClock.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(40)
class TransferIntegrationTests {
    private static final Instant AT = Instant.parse("2026-10-07T12:00:00Z");
    private static final Instant EXECUTED_AT = AT.plusSeconds(3600);
    private static final String SCHEMA = "transfer_test_" + UUID.randomUUID().toString().replace("-", "");

    @TestConfiguration(proxyBeanMethods = false)
    static class DeterministicClock {
        @Bean
        @Primary
        Clock testClock() { return Clock.fixed(EXECUTED_AT, ZoneOffset.UTC); }
    }

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired private TransferService service;
    @Autowired private TransferRepository transfers;
    @Autowired private TransferIdempotencyRepository idempotency;
    @Autowired private AccountRepository accounts;
    @Autowired private LedgerBalanceService balances;
    @Autowired private LedgerPostingService posting;
    @Autowired private LedgerEntryRepository entries;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private Flyway flyway;
    @PersistenceContext private EntityManager em;

    private TransactionTemplate transactions() {
        TransactionTemplate result = new TransactionTemplate(manager);
        result.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        result.setTimeout(15);
        return result;
    }

    private record Fixture(Account source, Account destination, LedgerAccount debit, LedgerAccount credit, LedgerAccount cash) {
    }

    private Fixture fixture() { return fixture("MXN", "MXN", true); }

    private Fixture fixture(String sourceCurrency, String destinationCurrency, boolean withLedgers) {
        return transactions().execute(status -> {
            Customer owner = Customer.create("Ana", "Perez", null, AT);
            Account source = Account.create(owner, AccountType.CHECKING, sourceCurrency, AT);
            Account destination = Account.create(owner, AccountType.SAVINGS, destinationCurrency, AT);
            em.persist(owner);
            em.persist(source);
            em.persist(destination);
            LedgerAccount debit = LedgerAccount.forAccount(source, AT);
            LedgerAccount credit = LedgerAccount.forAccount(destination, AT);
            LedgerAccount cash = LedgerAccount.internal("CASH_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(),
                    LedgerAccountCategory.ASSET, sourceCurrency, AT);
            if (withLedgers) {
                em.persist(debit);
                em.persist(credit);
                em.persist(cash);
            }
            em.flush();
            return new Fixture(source, destination, debit, credit, cash);
        });
    }

    // Balanced technical fixtures only: no deposit or withdrawal business use case is introduced.
    private JournalTransaction fund(Fixture f, String amount) {
        return post(f.cash(), f.debit(), amount, "fund-fixture");
    }

    private JournalTransaction post(LedgerAccount debit, LedgerAccount credit, String amount, String reference) {
        JournalTransaction journal = JournalTransaction.draft(debit.getCurrencyCode(), reference, AT);
        return posting.post(journal, List.of(
                LedgerEntry.create(journal, debit, 1, EntrySide.DEBIT, new BigDecimal(amount)),
                LedgerEntry.create(journal, credit, 2, EntrySide.CREDIT, new BigDecimal(amount))), AT.plusSeconds(1));
    }

    private String key() { return UUID.randomUUID().toString(); }

    private TransferCommand command(Fixture f, String amount, String key) {
        return new TransferCommand(f.source().getId(), f.destination().getId(), new BigDecimal(amount),
                f.source().getCurrencyCode(), key, "original-reference");
    }

    private TransferException rejected(TransferCommand command, TransferErrorCode code) {
        Throwable failure = catchThrowable(() -> service.execute(command));
        assertThat(failure).isInstanceOf(TransferException.class);
        assertThat(((TransferException) failure).getCode()).isEqualTo(code);
        return (TransferException) failure;
    }

    private Map<String, Object> record(String key) {
        return jdbc.queryForMap("SELECT * FROM transfer_idempotency_records WHERE idempotency_key = ?", key);
    }

    private long count(String table) {
        // Table names are test-owned literals, never user input.
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void transfers500WithExactLedgerAndLazyRelationshipsAndDeterministicTimestamps() {
        Fixture f = fixture();
        fund(f, "800");
        TransferCommand command = command(f, "500.00", key());
        TransferResult result = service.execute(command);
        assertThat(result.amount()).isEqualTo(new BigDecimal("500.0000"));
        assertThat(result.createdAt()).isEqualTo(EXECUTED_AT);
        assertThat(result.completedAt()).isEqualTo(EXECUTED_AT);
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300");
        assertThat(balances.getBalance(f.credit().getId())).isEqualByComparingTo("500");
        assertThat(balances.getBalance(f.debit().getId()).add(balances.getBalance(f.credit().getId())))
                .isEqualByComparingTo("800");
        assertThat(record(command.idempotencyKey())).containsEntry("status", "SUCCEEDED")
                .containsEntry("transfer_id", result.id()).containsEntry("failure_code", null);
        transactions().executeWithoutResult(status -> {
            Transfer persisted = transfers.findById(result.id()).orElseThrow();
            assertThat(Hibernate.isInitialized(persisted.getSourceAccount())).isFalse();
            assertThat(Hibernate.isInitialized(persisted.getDestinationAccount())).isFalse();
            assertThat(persisted.getSourceAccount().getId()).isEqualTo(f.source().getId());
            assertThat(persisted.getDestinationAccount().getId()).isEqualTo(f.destination().getId());
            assertThat(persisted.getJournalTransaction().getStatus()).isEqualTo(JournalStatus.POSTED);
            List<LedgerEntry> lines = entries.findAllByJournalTransactionIdOrderByLineNumberAsc(result.journalTransactionId());
            assertThat(lines).hasSize(2);
            assertThat(lines).extracting(LedgerEntry::getSide).containsExactly(EntrySide.DEBIT, EntrySide.CREDIT);
            assertThat(lines).extracting(line -> line.getLedgerAccount().getId())
                    .containsExactly(f.debit().getId(), f.credit().getId());
            assertThat(lines).allSatisfy(line -> assertThat(line.getAmount()).isEqualTo(new BigDecimal("500.0000")));
            TransferIdempotencyRecord persistedRecord = em.find(TransferIdempotencyRecord.class, record(command.idempotencyKey()).get("id"));
            assertThat(persistedRecord.getStatus()).isEqualTo(TransferIdempotencyStatus.SUCCEEDED);
            assertThat(persistedRecord.getResolvedAt()).isEqualTo(EXECUTED_AT);
            assertThat(persistedRecord.getTransfer().getId()).isEqualTo(result.id());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "JPY"})
    void supportedCurrenciesCanReallyTransfer(String currency) {
        Fixture f = fixture(currency, currency, true);
        fund(f, "800");
        TransferResult result = service.execute(command(f, currency.equals("JPY") ? "500" : "500.12", key()));
        assertThat(result.currencyCode()).isEqualTo(currency);
        assertThat(balances.getBalance(f.debit().getId()).add(balances.getBalance(f.credit().getId())))
                .isEqualByComparingTo("800");
    }

    @Test
    void insufficientFundsIsCommittedAndExceptionDoesNotEraseItAndReplayIgnoresLaterFunding() {
        Fixture f = fixture();
        TransferCommand request = command(f, "500", key());
        long journalsBefore = count("journal_transactions");
        rejected(request, TransferErrorCode.INSUFFICIENT_FUNDS);
        Map<String, Object> original = record(request.idempotencyKey());
        assertThat(original).containsEntry("status", "REJECTED").containsEntry("transfer_id", null)
                .containsEntry("failure_code", "INSUFFICIENT_FUNDS");
        assertThat(original.get("resolved_at")).isNotNull();
        assertThat(count("journal_transactions")).isEqualTo(journalsBefore);
        fund(f, "1000");
        rejected(request, TransferErrorCode.INSUFFICIENT_FUNDS);
        assertThat(record(request.idempotencyKey())).isEqualTo(original);
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("1000");
        assertThat(balances.getBalance(f.credit().getId())).isEqualByComparingTo("0");
        assertThat(count("journal_transactions")).isEqualTo(journalsBefore + 1);
        // A different key can use the now-funded account.
        assertThat(service.execute(command(f, "500", key())).id()).isNotNull();
    }

    @ParameterizedTest
    @MethodSource("inactiveAccounts")
    void inactiveAccountsRejectOrdinaryTransfersInBothDirections(boolean source, AccountStatus accountStatus,
                                                               TransferErrorCode expected) {
        Fixture f = fixture();
        fund(f, "800");
        transactions().executeWithoutResult(status -> {
            Account account = accounts.findById(source ? f.source().getId() : f.destination().getId()).orElseThrow();
            if (accountStatus == AccountStatus.CLOSED) { account.close(AT.plusSeconds(1)); }
            else { account.block(AT.plusSeconds(1)); }
        });
        TransferCommand request = command(f, "500", key());
        rejected(request, expected);
        assertThat(record(request.idempotencyKey())).containsEntry("status", "REJECTED").containsEntry("failure_code", expected.name());
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("800");
        assertThat(balances.getBalance(f.credit().getId())).isEqualByComparingTo("0");
    }

    static Stream<Arguments> inactiveAccounts() {
        return Stream.of(Arguments.of(true, AccountStatus.BLOCKED, TransferErrorCode.ACCOUNT_BLOCKED),
                Arguments.of(false, AccountStatus.BLOCKED, TransferErrorCode.ACCOUNT_BLOCKED),
                Arguments.of(true, AccountStatus.CLOSED, TransferErrorCode.ACCOUNT_CLOSED),
                Arguments.of(false, AccountStatus.CLOSED, TransferErrorCode.ACCOUNT_CLOSED));
    }

    @Test
    void rejectsSameAccountAfterReservingAndReplaysIt() {
        Fixture f = fixture();
        TransferCommand request = new TransferCommand(f.source().getId(), f.source().getId(), BigDecimal.ONE, "MXN", key(), null);
        rejected(request, TransferErrorCode.SAME_ACCOUNT);
        rejected(request, TransferErrorCode.SAME_ACCOUNT);
        assertThat(record(request.idempotencyKey())).containsEntry("failure_code", "SAME_ACCOUNT");
    }

    @Test
    void rejectsCrossCurrencyAndMismatchBetweenRequestAndBothAccounts() {
        Fixture mixed = fixture("MXN", "USD", true);
        TransferCommand request = command(mixed, "1", key());
        rejected(request, TransferErrorCode.CURRENCY_MISMATCH);
        assertThat(record(request.idempotencyKey())).containsEntry("failure_code", "CURRENCY_MISMATCH");
        Fixture same = fixture();
        rejected(new TransferCommand(same.source().getId(), same.destination().getId(), BigDecimal.ONE, "USD", key(), null),
                TransferErrorCode.CURRENCY_MISMATCH);
    }

    @Test
    void missingBankOrLedgerAccountIsAStableBusinessRejection() {
        Fixture f = fixture("MXN", "MXN", false);
        rejected(command(f, "1", key()), TransferErrorCode.LEDGER_ACCOUNT_NOT_FOUND);
        rejected(new TransferCommand(UUID.randomUUID(), f.destination().getId(), BigDecimal.ONE, "MXN", key(), null),
                TransferErrorCode.ACCOUNT_NOT_FOUND);
        rejected(new TransferCommand(f.source().getId(), UUID.randomUUID(), BigDecimal.ONE, "MXN", key(), null),
                TransferErrorCode.ACCOUNT_NOT_FOUND);
    }

    @ParameterizedTest
    @MethodSource("invalidRequests")
    void invalidInputCreatesNoReservation(String currency, String amount, TransferErrorCode expected) {
        Fixture f = fixture();
        TransferCommand request = new TransferCommand(f.source().getId(), f.destination().getId(),
                amount == null ? null : new BigDecimal(amount), currency, key(), null);
        rejected(request, expected);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE idempotency_key = ?",
                Integer.class, request.idempotencyKey())).isZero();
    }

    static Stream<Arguments> invalidRequests() {
        return Stream.of(Arguments.of("MXN", "0", TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("MXN", "-1", TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("MXN", null, TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("MXN", "1.001", TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("USD", "1.001", TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("JPY", "1.01", TransferErrorCode.INVALID_AMOUNT),
                Arguments.of("EUR", "1", TransferErrorCode.INVALID_CURRENCY));
    }

    @Test
    void equivalentRetryReturnsOriginalResultAndReferenceWithoutAnotherJournal() {
        Fixture f = fixture();
        fund(f, "800");
        String key = key();
        TransferResult original = service.execute(command(f, "500", key));
        long journals = count("journal_transactions");
        TransferResult replay = service.execute(new TransferCommand(f.source().getId(), f.destination().getId(),
                new BigDecimal("500.00"), "mxn", key, "changed-reference"));
        assertThat(replay).isEqualTo(original);
        assertThat(replay.reference()).isEqualTo("original-reference");
        assertThat(jdbc.queryForObject("SELECT reference FROM journal_transactions WHERE id = ?", String.class,
                replay.journalTransactionId())).isEqualTo("original-reference");
        assertThat(count("journal_transactions")).isEqualTo(journals);
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300");
    }

    @Test
    void differentEconomicRequestConflictsWithoutChangingOriginalRecord() {
        Fixture f = fixture();
        fund(f, "800");
        String key = key();
        service.execute(command(f, "500", key));
        Map<String, Object> original = record(key);
        rejected(command(f, "501", key), TransferErrorCode.IDEMPOTENCY_CONFLICT);
        rejected(new TransferCommand(f.destination().getId(), f.source().getId(), new BigDecimal("500"), "MXN", key, null),
                TransferErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(record(key)).isEqualTo(original);
    }

    @Test
    void cannotBeCalledInsideAnAmbientTransaction() {
        Fixture f = fixture();
        assertThatIllegalStateException().isThrownBy(() -> transactions().executeWithoutResult(status -> service.execute(command(f, "1", key()))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT", "COMMIT"})
    void technicalFailureAfterPostingRollsBackJournalEntriesTransferAndReservation(String failurePhase) {
        Fixture f = fixture();
        fund(f, "800");
        TransferCommand request = command(f, "500", key());
        long journals = count("journal_transactions");
        long lines = count("ledger_entries");
        long transfersBefore = count("transfers");
        jdbc.execute("""
                CREATE FUNCTION test_fail_transfer() RETURNS TRIGGER LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'simulated technical failure after posting' USING ERRCODE = 'XX000'; END; $$
                """);
        jdbc.execute(failurePhase.equals("INSERT")
                ? "CREATE TRIGGER test_fail_transfer BEFORE INSERT ON transfers FOR EACH ROW EXECUTE FUNCTION test_fail_transfer()"
                : "CREATE CONSTRAINT TRIGGER test_fail_transfer AFTER INSERT ON transfers DEFERRABLE INITIALLY DEFERRED "
                    + "FOR EACH ROW EXECUTE FUNCTION test_fail_transfer()");
        try {
            Throwable failure = catchThrowable(() -> service.execute(request));
            assertThat(failure).isNotNull().isNotInstanceOf(TransferException.class);
            assertSqlState(failure, "XX000");
        } finally {
            jdbc.execute("DROP TRIGGER test_fail_transfer ON transfers");
            jdbc.execute("DROP FUNCTION test_fail_transfer()");
        }
        assertThat(count("journal_transactions")).isEqualTo(journals);
        assertThat(count("ledger_entries")).isEqualTo(lines);
        assertThat(count("transfers")).isEqualTo(transfersBefore);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE idempotency_key = ?", Integer.class,
                request.idempotencyKey())).isZero();
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("800");
        // Technical failure does not consume the key.
        assertThat(service.execute(request).id()).isNotNull();
    }

    @Test
    void aRealCommitOfAnUnresolvedReservationIsRejectedByTheDeferredGuard() {
        String key = key();
        Throwable failure = catchThrowable(() -> transactions().executeWithoutResult(status -> {
            idempotency.reserve(key, "a".repeat(64), AT).orElseThrow();
            assertThat(jdbc.queryForObject("SELECT status FROM transfer_idempotency_records WHERE idempotency_key = ?", String.class, key))
                    .isEqualTo("RESERVED");
            // Callback ends normally: TransactionTemplate really attempts COMMIT.
        }));
        assertSqlState(failure, "23514");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE idempotency_key = ?", Integer.class, key)).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE transfers SET amount = amount", "DELETE FROM transfers",
            "UPDATE transfer_idempotency_records SET status = status", "DELETE FROM transfer_idempotency_records",
            "TRUNCATE transfers CASCADE", "TRUNCATE transfer_idempotency_records"})
    void financialAndTerminalIdempotencyRecordsRejectDirectSqlMutations(String sql) {
        Fixture f = fixture();
        fund(f, "800");
        service.execute(command(f, "500", key()));
        Throwable failure = catchThrowable(() -> transactions().executeWithoutResult(status -> jdbc.execute(sql)));
        assertSqlState(failure, "23514");
    }

    @Test
    void rejectedRecordsAreAlsoImmutableInSql() {
        Fixture f = fixture();
        String key = key();
        rejected(command(f, "500", key), TransferErrorCode.INSUFFICIENT_FUNDS);
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status ->
                jdbc.update("UPDATE transfer_idempotency_records SET failure_code = 'ACCOUNT_BLOCKED' WHERE idempotency_key = ?", key))), "23514");
        assertThat(record(key)).containsEntry("failure_code", "INSUFFICIENT_FUNDS");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SUCCEEDED", "REJECTED"})
    void terminalRowsCannotBeInsertedDirectly(String status) {
        String key = key();
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(transaction -> jdbc.update("""
                INSERT INTO transfer_idempotency_records (id,idempotency_key,request_hash,hash_version,status,created_at)
                VALUES (?,?,?,1,?,?)
                """, UUID.randomUUID(), key, "a".repeat(64), status, Timestamp.from(AT)))), "23514");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "status = 'SUCCEEDED', resolved_at = created_at",
            "status = 'REJECTED', failure_code = 'INSUFFICIENT_FUNDS'",
            "status = 'REJECTED', resolved_at = created_at",
            "status = 'REJECTED', failure_code = 'INVALID_AMOUNT', resolved_at = created_at",
            "status = 'REJECTED', failure_code = 'INSUFFICIENT_FUNDS', resolved_at = created_at - interval '1 second'",
            "status = 'REJECTED', failure_code = 'INSUFFICIENT_FUNDS', resolved_at = created_at, request_hash = repeat('b',64)"})
    void invalidFinalizationCannotPassSqlConstraints(String assignments) {
        String key = key();
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status -> {
            UUID id = idempotency.reserve(key, "a".repeat(64), AT).orElseThrow();
            jdbc.update("UPDATE transfer_idempotency_records SET " + assignments + " WHERE id = ?", id);
        })), "23514");
    }

    @ParameterizedTest
    @ValueSource(strings = {"wrong-amount", "reversed", "internal", "three-lines", "no-idempotency", "draft"})
    void deferredCommitChecksJournalAgainstTransferNotJustBalancedTotals(String violation) {
        Fixture f = fixture();
        Throwable failure = catchThrowable(() -> transactions().executeWithoutResult(status -> {
            LedgerAccount debit = violation.equals("reversed") ? f.credit() : violation.equals("internal") ? f.cash() : f.debit();
            LedgerAccount credit = violation.equals("reversed") ? f.debit() : f.credit();
            JournalTransaction journal = JournalTransaction.draft("MXN", "bad-transfer-fixture", AT);
            if (violation.equals("draft")) {
                em.persist(journal);
                em.flush();
            } else if (violation.equals("three-lines")) {
                posting.post(journal, List.of(LedgerEntry.create(journal, debit, 1, EntrySide.DEBIT, new BigDecimal("500")),
                        LedgerEntry.create(journal, credit, 2, EntrySide.CREDIT, new BigDecimal("250")),
                        LedgerEntry.create(journal, credit, 3, EntrySide.CREDIT, new BigDecimal("250"))), AT);
            } else {
                posting.post(journal, List.of(LedgerEntry.create(journal, debit, 1, EntrySide.DEBIT, new BigDecimal("500")),
                        LedgerEntry.create(journal, credit, 2, EntrySide.CREDIT, new BigDecimal("500"))), AT);
            }
            UUID transferId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO transfers (id,source_account_id,destination_account_id,amount,currency_code,
                        journal_transaction_id,created_at,completed_at) VALUES (?,?,?,?,'MXN',?,?,?)
                    """, transferId, f.source().getId(), f.destination().getId(),
                    new BigDecimal(violation.equals("wrong-amount") ? "400" : "500"), journal.getId(), Timestamp.from(AT), Timestamp.from(AT));
            if (!violation.equals("no-idempotency")) {
                UUID id = idempotency.reserve(key(), "a".repeat(64), AT).orElseThrow();
                idempotency.succeed(id, transferId, AT);
            }
            // No exception in this callback: PostgreSQL must reject the actual COMMIT.
        }));
        assertSqlState(failure, "23514");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_transactions WHERE reference = 'bad-transfer-fixture'", Integer.class)).isZero();
    }

    @Test
    void duplicateJournalCannotRepresentTwoTransfers() {
        Fixture f = fixture();
        fund(f, "800");
        TransferResult result = service.execute(command(f, "500", key()));
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status -> jdbc.update("""
                INSERT INTO transfers (id,source_account_id,destination_account_id,amount,currency_code,
                    journal_transaction_id,created_at,completed_at) VALUES (?,?,?,500,'MXN',?,?,?)
                """, UUID.randomUUID(), f.source().getId(), f.destination().getId(), result.journalTransactionId(),
                Timestamp.from(AT), Timestamp.from(AT)))), "23505");
    }

    @ParameterizedTest
    @ValueSource(strings = {"zero", "negative", "nan", "fractional-mxn", "same-account", "bad-time", "blank-reference",
            "unknown-source", "unknown-destination", "unknown-journal", "currency-mismatch"})
    void transferSqlChecksAndForeignKeysRejectInvalidData(String violation) {
        Fixture f = fixture();
        JournalTransaction journal = post(f.debit(), f.credit(), "500", "sql-check-fixture");
        UUID source = violation.equals("unknown-source") ? UUID.randomUUID() : f.source().getId();
        UUID destination = violation.equals("same-account") ? f.source().getId()
                : violation.equals("unknown-destination") ? UUID.randomUUID() : f.destination().getId();
        UUID journalId = violation.equals("unknown-journal") ? UUID.randomUUID() : journal.getId();
        String amount = switch (violation) {
            case "zero" -> "0";
            case "negative" -> "-1";
            case "nan" -> "NaN";
            case "fractional-mxn" -> "500.001";
            default -> "500";
        };
        String currency = violation.equals("currency-mismatch") ? "USD" : "MXN";
        String reference = violation.equals("blank-reference") ? "  " : null;
        Instant completed = violation.equals("bad-time") ? AT.minusSeconds(1) : AT;
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status -> jdbc.update("""
                INSERT INTO transfers (id,source_account_id,destination_account_id,amount,currency_code,reference,
                    journal_transaction_id,created_at,completed_at) VALUES (?,?,?,?::numeric,?,?,?,?,?)
                """, UUID.randomUUID(), source, destination, amount, currency, reference,
                journalId, Timestamp.from(AT), Timestamp.from(completed)))),
                violation.startsWith("unknown-") || violation.equals("currency-mismatch") ? "23503" : "23514");
    }

    @Test
    void jpyFractionalAmountCannotBypassGranularityThroughSql() {
        Fixture f = fixture("JPY", "JPY", true);
        JournalTransaction journal = post(f.debit(), f.credit(), "500.1", "sql-jpy-fixture");
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status -> jdbc.update("""
                INSERT INTO transfers (id,source_account_id,destination_account_id,amount,currency_code,
                    journal_transaction_id,created_at,completed_at) VALUES (?,?,?,500.1,'JPY',?,?,?)
                """, UUID.randomUUID(), f.source().getId(), f.destination().getId(), journal.getId(),
                Timestamp.from(AT), Timestamp.from(AT)))), "23514");
    }

    @Test
    void succeededRecordCannotPointToAMissingTransfer() {
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status -> {
            UUID reservation = idempotency.reserve(key(), "a".repeat(64), AT).orElseThrow();
            idempotency.succeed(reservation, UUID.randomUUID(), AT);
        })), "23503");
    }

    @Test
    void succeededRecordMustBeTheUniqueOwnerOfItsTransfer() {
        Fixture f = fixture();
        fund(f, "800");
        TransferResult transfer = service.execute(command(f, "500", key()));
        assertSqlState(catchThrowable(() -> transactions().executeWithoutResult(status -> {
            UUID reservation = idempotency.reserve(key(), "a".repeat(64), AT).orElseThrow();
            idempotency.succeed(reservation, transfer.id(), AT);
        })), "23505");
    }

    @Test
    void committedRejectionAlsoConflictsWithAnotherEconomicRequest() {
        Fixture f = fixture();
        String key = key();
        rejected(command(f, "500", key), TransferErrorCode.INSUFFICIENT_FUNDS);
        Map<String, Object> original = record(key);
        rejected(command(f, "400", key), TransferErrorCode.IDEMPOTENCY_CONFLICT);
        assertThat(record(key)).isEqualTo(original);
    }

    @Test
    void concurrentDuplicateWaitsForTheWinnerCommitOnADifferentDatabaseConnection() throws Exception {
        Fixture f = fixture();
        fund(f, "800");
        TransferCommand request = command(f, "500", key());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Integer> blockerPid = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = executor.submit(() -> transactions().executeWithoutResult(status -> {
                blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                accounts.findByIdForUpdate(TransferLockOrder.ordered(f.source().getId(), f.destination().getId()).getFirst()).orElseThrow();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            Future<TransferResult> first = executor.submit(() -> service.execute(request));
            Future<TransferResult> second = executor.submit(() -> service.execute(request));
            // A waits for the Account holder; B waits for A's uncommitted unique-key reservation.
            waitUntil(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity a JOIN pg_stat_activity b
                    ON a.pid = ANY(pg_blocking_pids(b.pid))
                    WHERE ? = ANY(pg_blocking_pids(a.pid)) AND a.pid <> b.pid
                    """, Integer.class, blockerPid.get()) > 0);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            TransferResult a = first.get(15, TimeUnit.SECONDS);
            TransferResult b = second.get(15, TimeUnit.SECONDS);
            assertThat(a).isEqualTo(b);
            assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300");
            assertThat(record(request.idempotencyKey())).containsEntry("status", "SUCCEEDED").containsEntry("transfer_id", a.id());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentDuplicatesUseExactlyOneJournalAndReturnIdenticalResults() throws Exception {
        Fixture f = fixture();
        fund(f, "800");
        TransferCommand request = command(f, "500", key());
        long before = count("journal_transactions");
        List<Object> results = race(request, request);
        assertThat(results).allSatisfy(result -> assertThat(result).isInstanceOf(TransferResult.class));
        assertThat(results.get(0)).isEqualTo(results.get(1));
        assertThat(count("journal_transactions")).isEqualTo(before + 1);
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE idempotency_key = ?", Integer.class,
                request.idempotencyKey())).isEqualTo(1);
    }

    @Test
    void concurrentDifferentRequestsForSameKeyHaveExactlyOneWinner() throws Exception {
        Fixture f = fixture();
        fund(f, "1000");
        String key = key();
        List<Object> results = race(command(f, "500", key), command(f, "400", key));
        assertThat(results.stream().filter(TransferResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(TransferErrorCode.IDEMPOTENCY_CONFLICT::equals)).hasSize(1);
        TransferResult winner = (TransferResult) results.stream().filter(TransferResult.class::isInstance).findFirst().orElseThrow();
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo(new BigDecimal("1000").subtract(winner.amount()));
        assertThat(balances.getBalance(f.debit().getId()).add(balances.getBalance(f.credit().getId()))).isEqualByComparingTo("1000");
    }

    @RepeatedTest(5)
    void twoConcurrent500RequestsAgainst800CannotOverdraw() throws Exception {
        Fixture f = fixture();
        fund(f, "800");
        TransferCommand first = command(f, "500", key());
        TransferCommand second = command(f, "500", key());
        List<Object> results = race(first, second);
        assertThat(results.stream().filter(TransferResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(TransferErrorCode.INSUFFICIENT_FUNDS::equals)).hasSize(1);
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300").isNotNegative();
        assertThat(balances.getBalance(f.credit().getId())).isEqualByComparingTo("500");
        assertThat(List.of(record(first.idempotencyKey()).get("status"), record(second.idempotencyKey()).get("status")))
                .containsExactlyInAnyOrder("SUCCEEDED", "REJECTED");
    }

    @RepeatedTest(5)
    void oppositeDirectionRequestsShareLockOrderAndFinishWithoutDeadlock() throws Exception {
        Fixture f = fixture();
        fund(f, "1000");
        post(f.cash(), f.credit(), "1000", "fund-destination-fixture");
        TransferCommand forward = command(f, "500", key());
        TransferCommand reverse = new TransferCommand(f.destination().getId(), f.source().getId(), new BigDecimal("500"), "MXN", key(), null);
        assertThat(race(forward, reverse)).allSatisfy(result -> assertThat(result).isInstanceOf(TransferResult.class));
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("1000");
        assertThat(balances.getBalance(f.credit().getId())).isEqualByComparingTo("1000");
    }

    @RepeatedTest(3)
    void competingTransfersToDifferentDestinationsStillSerializeOnTheirSharedSource() throws Exception {
        Fixture f = fixture();
        fund(f, "800");
        LedgerAccount third = transactions().execute(status -> {
            Customer owner = Customer.create("Luis", "Perez", null, AT);
            Account destination = Account.create(owner, AccountType.CHECKING, "MXN", AT);
            LedgerAccount ledger = LedgerAccount.forAccount(destination, AT);
            em.persist(owner);
            em.persist(destination);
            em.persist(ledger);
            em.flush();
            return ledger;
        });
        TransferCommand otherDestination = new TransferCommand(f.source().getId(), third.getAccount().getId(),
                new BigDecimal("500"), "MXN", key(), null);
        List<Object> results = race(command(f, "500", key()), otherDestination);
        assertThat(results.stream().filter(TransferResult.class::isInstance)).hasSize(1);
        assertThat(results.stream().filter(TransferErrorCode.INSUFFICIENT_FUNDS::equals)).hasSize(1);
        assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300").isNotNegative();
        assertThat(balances.getBalance(f.credit().getId()).add(balances.getBalance(third.getId())))
                .isEqualByComparingTo("500");
    }

    @Test
    void bothDirectionsReallyWaitOnTheSameFirstAccountBeforeEitherCanProceed() throws Exception {
        Fixture f = fixture();
        fund(f, "1000");
        post(f.cash(), f.credit(), "1000", "opposing-fixture");
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Integer> blockerPid = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            Future<?> blocker = executor.submit(() -> transactions().executeWithoutResult(status -> {
                blockerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                accounts.findByIdForUpdate(TransferLockOrder.ordered(f.source().getId(), f.destination().getId()).getFirst()).orElseThrow();
                held.countDown();
                await(release);
            }));
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
            Future<TransferResult> forward = executor.submit(() -> service.execute(command(f, "500", key())));
            Future<TransferResult> reverse = executor.submit(() -> service.execute(new TransferCommand(
                    f.destination().getId(), f.source().getId(), new BigDecimal("500"), "MXN", key(), null)));
            // PostgreSQL may queue the second waiter behind the first (a tuple lock),
            // so follow the complete blocking tree instead of expecting two direct edges.
            waitUntil(() -> jdbc.queryForObject("""
                    WITH RECURSIVE waiting(pid) AS (
                        SELECT ?::integer
                        UNION
                        SELECT a.pid FROM pg_stat_activity a JOIN waiting w
                            ON w.pid = ANY(pg_blocking_pids(a.pid))
                    ) SELECT count(*) - 1 FROM waiting
                    """, Integer.class, blockerPid.get()) >= 2);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
            assertThat(forward.get(15, TimeUnit.SECONDS).id()).isNotNull();
            assertThat(reverse.get(15, TimeUnit.SECONDS).id()).isNotNull();
            assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("1000");
            assertThat(balances.getBalance(f.credit().getId())).isEqualByComparingTo("1000");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private List<Object> race(TransferCommand first, TransferCommand second) throws Exception {
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Object> a = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return attempt(first); });
            Future<Object> b = executor.submit(() -> { start.await(5, TimeUnit.SECONDS); return attempt(second); });
            return List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private Object attempt(TransferCommand command) {
        try { return service.execute(command); }
        catch (TransferException exception) { return exception.getCode(); }
        // Technical failures intentionally escape the Future, making a concurrency test fail.
    }

    @Test
    void waitingAttemptCanOwnTheKeyAfterTheFirstReservationRollsBack() throws Exception {
        Fixture f = fixture();
        fund(f, "800");
        TransferCommand request = command(f, "500", key());
        String hash = TransferRequestHasher.hash(request.sourceAccountId(), request.destinationAccountId(), request.amount(), request.currencyCode());
        CountDownLatch reserved = new CountDownLatch(1);
        CountDownLatch rollback = new CountDownLatch(1);
        AtomicReference<UUID> abandoned = new AtomicReference<>();
        AtomicReference<Integer> ownerPid = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> owner = executor.submit(() -> transactions().executeWithoutResult(status -> {
                ownerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                abandoned.set(idempotency.reserve(request.idempotencyKey(), hash, AT).orElseThrow());
                reserved.countDown();
                await(rollback);
                throw new IllegalStateException("simulated technical rollback of owner");
            }));
            assertThat(reserved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<TransferResult> contender = executor.submit(() -> service.execute(request));
            // Observe a real PostgreSQL lock wait, rather than relying on a scheduling delay.
            waitUntil(() -> jdbc.queryForObject("""
                    SELECT count(*) FROM pg_stat_activity
                    WHERE ? = ANY(pg_blocking_pids(pid)) AND wait_event_type = 'Lock'
                    """, Integer.class, ownerPid.get()) > 0);
            rollback.countDown();
            assertThatThrownBy(() -> owner.get(10, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(IllegalStateException.class);
            TransferResult result = contender.get(15, TimeUnit.SECONDS);
            assertThat(record(request.idempotencyKey())).containsEntry("status", "SUCCEEDED").containsEntry("transfer_id", result.id());
            assertThat(record(request.idempotencyKey()).get("id")).isNotEqualTo(abandoned.get());
            assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("300");
        } finally {
            rollback.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void accountStateIsRevalidatedAfterWaitingForTheAccountLock() throws Exception {
        Fixture f = fixture();
        fund(f, "800");
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Integer> ownerPid = new AtomicReference<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> change = executor.submit(() -> transactions().executeWithoutResult(status -> {
                ownerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                Account account = accounts.findByIdForUpdate(f.source().getId()).orElseThrow();
                account.block(AT.plusSeconds(1));
                em.flush();
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            TransferCommand request = command(f, "500", key());
            Future<Object> waiting = executor.submit(() -> attempt(request));
            waitUntil(() -> jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE ? = ANY(pg_blocking_pids(pid))",
                    Integer.class, ownerPid.get()) > 0);
            release.countDown();
            change.get(10, TimeUnit.SECONDS);
            assertThat(waiting.get(15, TimeUnit.SECONDS)).isEqualTo(TransferErrorCode.ACCOUNT_BLOCKED);
            assertThat(record(request.idempotencyKey())).containsEntry("status", "REJECTED");
            assertThat(balances.getBalance(f.debit().getId())).isEqualByComparingTo("800");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try { assertThat(latch.await(8, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
    }

    private void waitUntil(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) { throw new AssertionError("Expected PostgreSQL lock wait was not observed"); }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
    }

    @Test
    void flywayAndHibernateValidateTheRealTransferSchema() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(em.getEntityManagerFactory().getProperties()).containsEntry("hibernate.hbm2ddl.auto", "validate");
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL ORDER BY installed_rank", String.class))
                .containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE status = 'RESERVED'", Integer.class)).isZero();
    }

    private void assertSqlState(Throwable failure, String expected) {
        assertThat(failure).isNotNull();
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) { current = current.getCause(); }
        assertThat(current).as("SQL cause of %s", failure).isInstanceOf(SQLException.class);
        assertThat(((SQLException) current).getSQLState()).isEqualTo(expected);
    }

    @AfterEach
    void noTestLeavesACommittedReservation() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer_idempotency_records WHERE status = 'RESERVED'", Integer.class)).isZero();
    }

    @AfterAll
    void dropOnlyTheGeneratedTestSchema() {
        if (!SCHEMA.matches("transfer_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe test schema name"); }
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
}
