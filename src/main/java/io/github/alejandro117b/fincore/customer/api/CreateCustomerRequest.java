package io.github.alejandro117b.fincore.customer.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record CreateCustomerRequest(
        @NotBlank @Size(max = 100) @Schema(example = "Ana") String firstName,
        @NotBlank @Size(max = 150) @Schema(example = "Perez") String lastName,
        @Email @Size(max = 254) @Schema(nullable = true, example = "ana@example.com") String email) {
}
