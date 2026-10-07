package io.github.alejandro117b.fincore.transfer.api;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record TransferResponse(UUID id, UUID sourceAccountId, UUID destinationAccountId,
        @Schema(type = "string", example = "500.0000", pattern = "[0-9]+\\.[0-9]{4}",
                description = "Exact amount with four decimal places, never a JSON number.")
        String amount, String currencyCode, @Schema(nullable = true) String reference,
        @Schema(description = "Trace identifier only; no public journal write operations.")
        UUID journalTransactionId, Instant createdAt, Instant completedAt) {
}
