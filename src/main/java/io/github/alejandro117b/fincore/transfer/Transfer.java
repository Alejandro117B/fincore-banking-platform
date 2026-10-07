package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.ledger.JournalStatus;
import io.github.alejandro117b.fincore.ledger.JournalTransaction;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "transfers")
public class Transfer {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_account_id", nullable = false, updatable = false)
    private Account sourceAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "destination_account_id", nullable = false, updatable = false)
    private Account destinationAccount;

    @Column(nullable = false, precision = 19, scale = 4, updatable = false)
    private BigDecimal amount;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Column(length = 128, updatable = false)
    private String reference;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_transaction_id", nullable = false, unique = true, updatable = false)
    private JournalTransaction journalTransaction;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "completed_at", nullable = false, updatable = false)
    private Instant completedAt;

    protected Transfer() {
    }

    static Transfer completed(Account source, Account destination, BigDecimal amount, String currency,
                              String reference, JournalTransaction journal, Instant createdAt, Instant completedAt) {
        Objects.requireNonNull(source);
        Objects.requireNonNull(destination);
        Objects.requireNonNull(journal);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(completedAt);
        String normalized = TransferCurrencyPolicy.normalizeCurrency(currency);
        if (source.getId().equals(destination.getId()) || journal.getStatus() != JournalStatus.POSTED
                || !normalized.equals(source.getCurrencyCode()) || !normalized.equals(destination.getCurrencyCode())
                || !normalized.equals(journal.getCurrencyCode()) || completedAt.isBefore(createdAt)
                || (reference != null && (reference.isBlank() || reference.length() > 128))) {
            throw new IllegalArgumentException("A completed transfer requires distinct accounts and a posted journal in its currency");
        }
        Transfer result = new Transfer();
        result.id = UUID.randomUUID();
        result.sourceAccount = source;
        result.destinationAccount = destination;
        result.amount = TransferCurrencyPolicy.normalizeAmount(amount, normalized);
        result.currencyCode = normalized;
        result.reference = reference;
        result.journalTransaction = journal;
        result.createdAt = createdAt;
        result.completedAt = completedAt;
        return result;
    }

    public UUID getId() { return id; }
    public Account getSourceAccount() { return sourceAccount; }
    public Account getDestinationAccount() { return destinationAccount; }
    public BigDecimal getAmount() { return amount; }
    public String getCurrencyCode() { return currencyCode; }
    public String getReference() { return reference; }
    public JournalTransaction getJournalTransaction() { return journalTransaction; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
