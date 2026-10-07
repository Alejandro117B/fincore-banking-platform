package io.github.alejandro117b.fincore.ledger;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "ledger_entries")
public class LedgerEntry {

    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_transaction_id", nullable = false, updatable = false)
    private JournalTransaction journalTransaction;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ledger_account_id", nullable = false, updatable = false)
    private LedgerAccount ledgerAccount;

    @Column(name = "line_number", nullable = false, updatable = false)
    private int lineNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    private EntrySide side;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    protected LedgerEntry() {
    }

    private LedgerEntry(JournalTransaction journal, LedgerAccount account, int lineNumber,
                        EntrySide side, BigDecimal amount) {
        if (journal == null || account == null || side == null || lineNumber <= 0) {
            throw new IllegalArgumentException("Journal, ledger account, side and positive line number are required");
        }
        if (journal.getStatus() != JournalStatus.DRAFT) {
            throw new IllegalStateException("Cannot add entries to a posted journal");
        }
        if (!journal.getCurrencyCode().equals(account.getCurrencyCode())) {
            throw new IllegalArgumentException("Journal and ledger account must share a currency");
        }
        this.amount = normalizeAmount(amount);
        this.id = UUID.randomUUID();
        this.journalTransaction = journal;
        this.ledgerAccount = account;
        this.lineNumber = lineNumber;
        this.side = side;
        this.currencyCode = journal.getCurrencyCode();
    }

    public static LedgerEntry create(JournalTransaction journal, LedgerAccount account, int lineNumber,
                                     EntrySide side, BigDecimal amount) {
        return new LedgerEntry(journal, account, lineNumber, side, amount);
    }

    static BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new IllegalArgumentException("Amount must be positive and fit NUMERIC(19,4)");
        }
        try {
            return amount.setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Amount cannot be represented exactly with four decimal places", exception);
        }
    }

    public UUID getId() {
        return id;
    }

    public JournalTransaction getJournalTransaction() {
        return journalTransaction;
    }

    public LedgerAccount getLedgerAccount() {
        return ledgerAccount;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    public EntrySide getSide() {
        return side;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }
}
