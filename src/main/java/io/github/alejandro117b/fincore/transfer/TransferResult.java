package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Immutable application result; does not leak detached lazy JPA relationships to consumers. */
public record TransferResult(UUID id, UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                             String currencyCode, String reference, UUID journalTransactionId,
                             Instant createdAt, Instant completedAt) {
    static TransferResult from(Transfer transfer) {
        return new TransferResult(transfer.getId(), transfer.getSourceAccount().getId(),
                transfer.getDestinationAccount().getId(), transfer.getAmount(), transfer.getCurrencyCode(),
                transfer.getReference(), transfer.getJournalTransaction().getId(),
                transfer.getCreatedAt(), transfer.getCompletedAt());
    }
}
