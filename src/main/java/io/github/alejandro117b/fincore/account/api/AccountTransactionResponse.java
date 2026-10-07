package io.github.alejandro117b.fincore.account.api;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record AccountTransactionResponse(UUID journalTransactionId, @Schema(nullable = true) UUID transferId, Instant postedAt,
        String currencyCode,
        @Schema(type = "string", example = "500.0000") String debitAmount,
        @Schema(type = "string", example = "0.0000") String creditAmount,
        @Schema(type = "string", example = "-500.0000", description = "LIABILITY: credits minus debits.")
        String netAmount, @Schema(nullable = true) String reference) {
}
