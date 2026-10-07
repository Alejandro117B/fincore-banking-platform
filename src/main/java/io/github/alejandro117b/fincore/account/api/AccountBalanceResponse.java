package io.github.alejandro117b.fincore.account.api;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record AccountBalanceResponse(UUID accountId, String currencyCode,
        @Schema(type = "string", pattern = "-?[0-9]+\\.[0-9]{4}", example = "0.0000",
                description = "Posted ledger balance only, exactly four decimal places. Not available balance.")
        String postedBalance, Instant calculatedAt) {
}
