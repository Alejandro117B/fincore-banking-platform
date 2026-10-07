package io.github.alejandro117b.fincore.account.api;

import java.time.Instant;
import java.util.UUID;
import io.github.alejandro117b.fincore.account.AccountStatus;
import io.github.alejandro117b.fincore.account.AccountType;
import io.swagger.v3.oas.annotations.media.Schema;

public record AccountResponse(UUID id, UUID customerId, AccountType type, AccountStatus status,
                              String currencyCode, Instant createdAt, Instant updatedAt, @Schema(nullable = true) Instant closedAt) {
}
