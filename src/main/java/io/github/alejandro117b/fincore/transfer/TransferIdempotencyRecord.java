package io.github.alejandro117b.fincore.transfer;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** Read model. Reservation/finalization use explicit SQL in the same JPA transaction. */
@Entity
@Table(name = "transfer_idempotency_records")
public class TransferIdempotencyRecord {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, updatable = false)
    private String requestHash;

    @Column(name = "hash_version", nullable = false, updatable = false)
    private int hashVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private TransferIdempotencyStatus status;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", unique = true, updatable = false)
    private Transfer transfer;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", length = 40, updatable = false)
    private TransferErrorCode failureCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "resolved_at", updatable = false)
    private Instant resolvedAt;

    protected TransferIdempotencyRecord() {
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public int getHashVersion() { return hashVersion; }
    public TransferIdempotencyStatus getStatus() { return status; }
    public Transfer getTransfer() { return transfer; }
    public TransferErrorCode getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getResolvedAt() { return resolvedAt; }
}
