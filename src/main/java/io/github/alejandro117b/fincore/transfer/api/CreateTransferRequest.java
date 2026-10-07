package io.github.alejandro117b.fincore.transfer.api;

import java.util.UUID;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTransferRequest(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @NotBlank @Size(max = 40)
        @Pattern(regexp = "-?(0|[1-9][0-9]*)(\\.[0-9]+)?")
        @Schema(type = "string", example = "500.00", maxLength = 40,
                pattern = "-?(0|[1-9][0-9]*)(\\.[0-9]+)?",
                description = "JSON string only; numeric tokens are rejected. Plain decimal, no exponent or thousands separators. Positive; MXN/USD 2 decimal places, JPY 0; no rounding.")
        String amount,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode,
        @Size(max = 128) @Pattern(regexp = "(?s).*\\S.*")
        @Schema(nullable = true, description = "Optional trace reference, excluded from the economic request hash. A replay preserves the original reference.")
        String reference) {
}
