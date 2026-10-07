package io.github.alejandro117b.fincore.ledger;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "journal_transactions")
public class JournalTransaction {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "currency_code", nullable = false, length = 3, updatable = false)
    private String currencyCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JournalStatus status;

    @Column(length = 128, updatable = false)
    private String reference;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    protected JournalTransaction() {
    }

    private JournalTransaction(String currencyCode, String reference, Instant at) {
        this.currencyCode = LedgerValidation.currencyCode(currencyCode);
        if (reference != null && (reference.isBlank() || reference.strip().length() > 128)) {
            throw new IllegalArgumentException("Reference must be nonblank and at most 128 characters when supplied");
        }
        this.reference = reference == null ? null : reference.strip();
        this.createdAt = LedgerValidation.timestamp(at);
        this.id = UUID.randomUUID();
        this.status = JournalStatus.DRAFT;
    }

    public static JournalTransaction draft(String currencyCode, String reference, Instant at) {
        return new JournalTransaction(currencyCode, reference, at);
    }

    void validatePostingTime(Instant at) {
        if (status != JournalStatus.DRAFT) {
            throw new IllegalStateException("Only a draft journal can be posted");
        }
        if (LedgerValidation.timestamp(at).isBefore(createdAt)) {
            throw new IllegalArgumentException("Posting time must not precede creation time");
        }
    }

    void markPosted(Instant at) {
        validatePostingTime(at);
        status = JournalStatus.POSTED;
        postedAt = at;
    }

    public UUID getId() {
        return id;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public JournalStatus getStatus() {
        return status;
    }

    public String getReference() {
        return reference;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPostedAt() {
        return postedAt;
    }
}
