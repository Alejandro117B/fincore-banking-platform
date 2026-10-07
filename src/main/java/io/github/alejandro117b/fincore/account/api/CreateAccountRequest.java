package io.github.alejandro117b.fincore.account.api;

import java.util.UUID;
import io.github.alejandro117b.fincore.account.AccountType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record CreateAccountRequest(
        @NotNull UUID customerId,
        @NotNull AccountType type,
        @NotBlank @Pattern(regexp = "[A-Za-z]{3}") String currencyCode) {
}
