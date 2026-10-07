package io.github.alejandro117b.fincore.api;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.UUID;

import org.springframework.http.HttpStatus;

public final class ApiInputs {
    private ApiInputs() {
    }

    public static UUID uuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_UUID", "A canonical UUID is required.");
        }
        return UUID.fromString(value);
    }

    public static String money(BigDecimal amount) {
        return amount.setScale(4, RoundingMode.UNNECESSARY).toPlainString();
    }
}
