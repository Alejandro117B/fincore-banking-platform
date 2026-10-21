package io.github.alejandro117b.fincore.transfer;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.github.alejandro117b.fincore.security.CurrentActor;
import io.github.alejandro117b.fincore.security.ResourceAccessPolicy;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.account.AccountRepository;
import io.github.alejandro117b.fincore.account.AccountStatus;
import io.github.alejandro117b.fincore.ledger.EntrySide;
import io.github.alejandro117b.fincore.ledger.JournalTransaction;
import io.github.alejandro117b.fincore.ledger.LedgerAccount;
import io.github.alejandro117b.fincore.ledger.LedgerAccountCategory;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerBalanceService;
import io.github.alejandro117b.fincore.ledger.LedgerEntry;
import io.github.alejandro117b.fincore.ledger.LedgerPostingService;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TransferService {
    private final TransactionTemplate transactions;
    private final AccountRepository accounts;
    private final LedgerAccountRepository ledgerAccounts;
    private final LedgerBalanceService balances;
    private final LedgerPostingService posting;
    private final TransferRepository transfers;
    private final TransferIdempotencyRepository idempotency;
    private final EntityManager entityManager;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ResourceAccessPolicy access;

    public TransferService(PlatformTransactionManager manager, AccountRepository accounts,
                           LedgerAccountRepository ledgerAccounts, LedgerBalanceService balances,
                           LedgerPostingService posting, TransferRepository transfers,
                           TransferIdempotencyRepository idempotency, EntityManager entityManager,
                           JdbcTemplate jdbc, Clock clock, ResourceAccessPolicy access) {
        this.transactions = new TransactionTemplate(manager);
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setTimeout(15);
        this.accounts = accounts;
        this.ledgerAccounts = ledgerAccounts;
        this.balances = balances;
        this.posting = posting;
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.entityManager = entityManager;
        this.jdbc = jdbc;
        this.clock = clock;
        this.access = access;
    }

    public TransferResult execute(TransferCommand command) {
        // Owning the boundary guarantees that a business exception is thrown only AFTER commit.
        // It also prevents reading previously managed/stale Account state when acquiring locks.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("TransferService must be invoked outside an existing transaction");
        }
        CurrentActor actor = access.requireUser();
        TransferCommand normalized = normalize(command);
        String hash = TransferRequestHasher.hash(normalized.sourceAccountId(), normalized.destinationAccountId(),
                normalized.amount(), normalized.currencyCode());
        TransferExecutionOutcome outcome = transactions.execute(status -> executeInTransaction(normalized, hash, actor));
        if (outcome instanceof TransferExecutionOutcome.Success success) {
            return success.transfer();
        }
        if (outcome instanceof TransferExecutionOutcome.Rejected rejected) {
            throw new TransferException(rejected.code());
        }
        throw new IllegalStateException("Missing transfer outcome");
    }

    private TransferExecutionOutcome executeInTransaction(TransferCommand command, String hash, CurrentActor actor) {
        // Transaction-local PostgreSQL setting; lock timeouts are technical failures, never REJECTED.
        jdbc.execute("SET LOCAL lock_timeout = '10s'");
        access.requireOwnAccount(command.sourceAccountId());
        idempotency.requireAvailableKey(command.idempotencyKey());
        Instant createdAt = now();
        var reservation = idempotency.reserve(actor.userId(), command.idempotencyKey(), hash, createdAt);
        // A conflicting INSERT can wait for another transaction. Recheck current ownership afterward.
        access.requireOwnAccount(command.sourceAccountId());
        if (reservation.isEmpty()) {
            var previous = idempotency.findByRequesterAndKey(actor.userId(), command.idempotencyKey());
            if (previous.hashVersion() != TransferRequestHasher.HASH_VERSION || !previous.hash().equals(hash)) {
                return new TransferExecutionOutcome.Rejected(TransferErrorCode.IDEMPOTENCY_CONFLICT);
            }
            return switch (previous.status()) {
                case SUCCEEDED -> new TransferExecutionOutcome.Success(transfers.findReplayResult(
                        previous.id(), actor.userId(), hash, TransferRequestHasher.HASH_VERSION, actor.customerId())
                        .orElseThrow(() -> {
                            // Ownership may change between the scalar check and this filtered projection.
                            access.requireOwnAccount(command.sourceAccountId());
                            return new IllegalStateException("Successful reservation has no authorized transfer");
                        }));
                case REJECTED -> new TransferExecutionOutcome.Rejected(previous.failureCode());
                case RESERVED -> throw new IllegalStateException("A committed reservation must be terminal");
            };
        }
        UUID reservationId = reservation.orElseThrow();
        UUID sourceId = command.sourceAccountId();
        UUID destinationId = command.destinationAccountId();
        if (sourceId.equals(destinationId)) {
            return reject(actor.userId(), reservationId, TransferErrorCode.SAME_ACCOUNT);
        }
        Map<UUID, Account> lockedAccounts = new HashMap<>();
        for (UUID id : TransferLockOrder.ordered(sourceId, destinationId)) {
            accounts.findByIdForUpdate(id).ifPresent(account -> lockedAccounts.put(id, account));
        }
        access.requireOwnLockedAccount(lockedAccounts.get(sourceId), actor);
        if (lockedAccounts.size() != 2) {
            return reject(actor.userId(), reservationId, TransferErrorCode.ACCOUNT_NOT_FOUND);
        }
        Account source = lockedAccounts.get(sourceId);
        Account destination = lockedAccounts.get(destinationId);
        for (Account account : List.of(source, destination)) {
            if (account.getStatus() == AccountStatus.BLOCKED) {
                return reject(actor.userId(), reservationId, TransferErrorCode.ACCOUNT_BLOCKED);
            }
            if (account.getStatus() == AccountStatus.CLOSED) {
                return reject(actor.userId(), reservationId, TransferErrorCode.ACCOUNT_CLOSED);
            }
        }
        for (Account account : List.of(source, destination)) {
            if (!command.currencyCode().equals(account.getCurrencyCode())) {
                return reject(actor.userId(), reservationId, TransferErrorCode.CURRENCY_MISMATCH);
            }
        }
        var sourceLedger = ledgerAccounts.findByAccountId(sourceId);
        var destinationLedger = ledgerAccounts.findByAccountId(destinationId);
        if (sourceLedger.isEmpty() || destinationLedger.isEmpty()) {
            return reject(actor.userId(), reservationId, TransferErrorCode.LEDGER_ACCOUNT_NOT_FOUND);
        }
        Map<UUID, LedgerAccount> lockedLedgers = new HashMap<>();
        for (UUID id : TransferLockOrder.ordered(sourceLedger.orElseThrow().getId(), destinationLedger.orElseThrow().getId())) {
            lockedLedgers.put(id, ledgerAccounts.findByIdForUpdate(id).orElseThrow());
        }
        LedgerAccount debit = lockedLedgers.get(sourceLedger.orElseThrow().getId());
        LedgerAccount credit = lockedLedgers.get(destinationLedger.orElseThrow().getId());
        verifyLedger(debit, sourceId, command.currencyCode());
        verifyLedger(credit, destinationId, command.currencyCode());
        if (balances.getBalance(debit.getId()).compareTo(command.amount()) < 0) {
            return reject(actor.userId(), reservationId, TransferErrorCode.INSUFFICIENT_FUNDS);
        }
        JournalTransaction journal = JournalTransaction.draft(command.currencyCode(), command.reference(), createdAt);
        posting.post(journal, List.of(
                LedgerEntry.create(journal, debit, 1, EntrySide.DEBIT, command.amount()),
                LedgerEntry.create(journal, credit, 2, EntrySide.CREDIT, command.amount())), now());
        Instant completedAt = now();
        Transfer transfer = Transfer.completed(source, destination, command.amount(), command.currencyCode(),
                command.reference(), journal, createdAt, completedAt);
        entityManager.persist(transfer);
        // JDBC finalization has an immediate FK to Transfer: make its INSERT visible first.
        entityManager.flush();
        idempotency.succeed(reservationId, actor.userId(), transfer.getId(), completedAt);
        entityManager.flush();
        return new TransferExecutionOutcome.Success(TransferResult.from(transfer));
    }

    private TransferExecutionOutcome reject(UUID requester, UUID reservationId, TransferErrorCode code) {
        idempotency.reject(reservationId, requester, code, now());
        return new TransferExecutionOutcome.Rejected(code);
    }

    private void verifyLedger(LedgerAccount ledger, UUID bankAccountId, String currency) {
        if (ledger.getCategory() != LedgerAccountCategory.LIABILITY || ledger.getAccount() == null
                || !bankAccountId.equals(ledger.getAccount().getId()) || !currency.equals(ledger.getCurrencyCode())) {
            throw new IllegalStateException("Customer ledger account has inconsistent metadata");
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    static TransferCommand normalize(TransferCommand command) {
        if (command == null || command.sourceAccountId() == null || command.destinationAccountId() == null
                || command.idempotencyKey() == null || !command.idempotencyKey().matches("[A-Za-z0-9._:-]{1,128}")) {
            throw new TransferException(TransferErrorCode.INVALID_REQUEST);
        }
        String reference = command.reference() == null ? null : command.reference().strip();
        if (reference != null && (reference.isBlank() || reference.length() > 128)) {
            throw new TransferException(TransferErrorCode.INVALID_REQUEST);
        }
        String currency = TransferCurrencyPolicy.normalizeCurrency(command.currencyCode());
        return new TransferCommand(command.sourceAccountId(), command.destinationAccountId(),
                TransferCurrencyPolicy.normalizeAmount(command.amount(), currency), currency,
                command.idempotencyKey(), reference);
    }
}
