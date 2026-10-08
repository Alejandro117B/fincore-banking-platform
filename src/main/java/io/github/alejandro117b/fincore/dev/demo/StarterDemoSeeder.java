package io.github.alejandro117b.fincore.dev.demo;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import io.github.alejandro117b.fincore.account.AccountService;
import io.github.alejandro117b.fincore.account.AccountType;
import io.github.alejandro117b.fincore.customer.CustomerService;
import io.github.alejandro117b.fincore.ledger.EntrySide;
import io.github.alejandro117b.fincore.ledger.JournalTransaction;
import io.github.alejandro117b.fincore.ledger.LedgerAccount;
import io.github.alejandro117b.fincore.ledger.LedgerAccountCategory;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerEntry;
import io.github.alejandro117b.fincore.ledger.LedgerPostingService;
import jakarta.persistence.EntityManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** A single development fixture, instantiated only by DemoSeedConfiguration. */
public class StarterDemoSeeder {
    public static final String SCENARIO_KEY = "starter-mxn-v1";
    private static final String CASH_CODE = "DEMO_CASH_MXN";
    private static final String CURRENCY = "MXN";
    private static final BigDecimal OPENING_AMOUNT = new BigDecimal("2000.0000");
    // Stable across processes and JVMs. Separate namespace from Flyway's locks.
    static final int LOCK_NAMESPACE = 117;
    static final int LOCK_KEY = 20261007;

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final EntityManager em;
    private final CustomerService customers;
    private final AccountService accounts;
    private final LedgerAccountRepository ledgers;
    private final LedgerPostingService posting;
    private final Clock clock;

    public StarterDemoSeeder(PlatformTransactionManager manager, JdbcTemplate jdbc, EntityManager em,
                             CustomerService customers, AccountService accounts,
                             LedgerAccountRepository ledgers, LedgerPostingService posting, Clock clock) {
        transactions = new TransactionTemplate(manager);
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transactions.setTimeout(30);
        this.jdbc = jdbc;
        this.em = em;
        this.customers = customers;
        this.accounts = accounts;
        this.ledgers = ledgers;
        this.posting = posting;
        this.clock = clock;
    }

    public record SeedIds(UUID alejandroCustomerId, UUID fernandoCustomerId,
                          UUID alejandroAccountId, UUID fernandoAccountId, UUID fundingJournalId) {
    }

    public SeedIds seed() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Demo seeding must own its transaction boundary");
        }
        return transactions.execute(status -> seedInTransaction());
    }

    private SeedIds seedInTransaction() {
        jdbc.execute("SET LOCAL lock_timeout = '10s'");
        jdbc.query("SELECT pg_advisory_xact_lock(?, ?)",
                rs -> { rs.next(); return null; }, LOCK_NAMESPACE, LOCK_KEY);
        String hash = definitionHash();
        var existing = jdbc.query("SELECT * FROM demo_seed_runs WHERE scenario_key = ?", (rs, row) -> {
            if (!hash.equals(rs.getString("definition_hash"))) {
                throw new IllegalStateException("Demo scenario " + SCENARIO_KEY
                        + " definition_hash mismatch; no funds were changed");
            }
            SeedIds ids = new SeedIds(rs.getObject("alejandro_customer_id", UUID.class),
                    rs.getObject("fernando_customer_id", UUID.class),
                    rs.getObject("alejandro_account_id", UUID.class),
                    rs.getObject("fernando_account_id", UUID.class),
                    rs.getObject("funding_journal_id", UUID.class));
            if (ids.alejandroCustomerId() == null || ids.fernandoCustomerId() == null
                    || ids.alejandroAccountId() == null || ids.fernandoAccountId() == null
                    || ids.fundingJournalId() == null) {
                throw new IllegalStateException("Demo scenario " + SCENARIO_KEY + " has incomplete metadata");
            }
            return ids;
        }, SCENARIO_KEY);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }

        Instant at = clock.instant().truncatedTo(ChronoUnit.MICROS);
        // The PK also protects this reservation; it rolls back with every other write.
        jdbc.update("INSERT INTO demo_seed_runs (scenario_key, definition_hash, created_at) VALUES (?, ?, ?)",
                SCENARIO_KEY, hash, Timestamp.from(at));
        var alejandro = customers.create("Alejandro", "Demo", null);
        var fernando = customers.create("Fernando", "Demo", null);
        var source = accounts.create(alejandro.id(), AccountType.CHECKING, CURRENCY);
        var destination = accounts.create(fernando.id(), AccountType.SAVINGS, CURRENCY);
        LedgerAccount cash = cash(at);
        LedgerAccount liability = ledgers.findByAccountId(source.id()).orElseThrow();
        if (liability.getCategory() != LedgerAccountCategory.LIABILITY
                || !CURRENCY.equals(liability.getCurrencyCode())) {
            throw new IllegalStateException("Demo customer ledger must be LIABILITY in MXN");
        }
        JournalTransaction journal = JournalTransaction.draft(CURRENCY, SCENARIO_KEY + ":opening", at);
        posting.post(journal, List.of(
                LedgerEntry.create(journal, cash, 1, EntrySide.DEBIT, OPENING_AMOUNT),
                LedgerEntry.create(journal, liability, 2, EntrySide.CREDIT, OPENING_AMOUNT)),
                clock.instant().truncatedTo(ChronoUnit.MICROS));
        SeedIds ids = new SeedIds(alejandro.id(), fernando.id(), source.id(), destination.id(), journal.getId());
        jdbc.update("""
                UPDATE demo_seed_runs SET alejandro_customer_id = ?, fernando_customer_id = ?,
                    alejandro_account_id = ?, fernando_account_id = ?, funding_journal_id = ?
                WHERE scenario_key = ?
                """, ids.alejandroCustomerId(), ids.fernandoCustomerId(), ids.alejandroAccountId(),
                ids.fernandoAccountId(), ids.fundingJournalId(), SCENARIO_KEY);
        return ids;
    }

    private LedgerAccount cash(Instant at) {
        var matches = em.createQuery("select a from LedgerAccount a where a.systemCode = :code", LedgerAccount.class)
                .setParameter("code", CASH_CODE).getResultList();
        if (!matches.isEmpty()) {
            if (matches.size() != 1) {
                throw new IllegalStateException("Demo cash account has ambiguous currency metadata");
            }
            LedgerAccount cash = matches.getFirst();
            if (cash.getAccount() != null || cash.getCategory() != LedgerAccountCategory.ASSET
                    || !CURRENCY.equals(cash.getCurrencyCode())) {
                throw new IllegalStateException("DEMO_CASH_MXN must be an internal ASSET account in MXN");
            }
            return cash;
        }
        LedgerAccount cash = LedgerAccount.internal(CASH_CODE, LedgerAccountCategory.ASSET, CURRENCY, at);
        em.persist(cash);
        return cash;
    }

    private static String definitionHash() {
        String definition = String.join("\n", SCENARIO_KEY,
                "Alejandro|Demo|null|" + AccountType.CHECKING + "|" + CURRENCY + "|" + OPENING_AMOUNT.toPlainString(),
                "Fernando|Demo|null|" + AccountType.SAVINGS + "|" + CURRENCY + "|0.0000",
                CASH_CODE + "|" + LedgerAccountCategory.ASSET + "|" + CURRENCY + "|DEBIT",
                "customer|LIABILITY|CREDIT", SCENARIO_KEY + ":opening");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(definition.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required for demo scenario identification", exception);
        }
    }
}
