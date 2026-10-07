package io.github.alejandro117b.fincore.customer.api;

import java.time.Instant;
import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;

public record CustomerResponse(UUID id, String firstName, String lastName, @Schema(nullable = true) String email,
                               Instant createdAt, Instant updatedAt) {
}
