package io.github.alejandro117b.fincore.ledger;

import java.time.Instant;
import java.util.Currency;
import java.util.Locale;

final class LedgerValidation {

    private LedgerValidation() {
    }

    static String currencyCode(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Currency code is required");
        }
        String normalized = value.strip().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("Currency code must contain three ASCII letters");
        }
        Currency.getInstance(normalized);
        return normalized;
    }

    static Instant timestamp(Instant at) {
        if (at == null) {
            throw new IllegalArgumentException("Timestamp is required");
        }
        return at;
    }
}
