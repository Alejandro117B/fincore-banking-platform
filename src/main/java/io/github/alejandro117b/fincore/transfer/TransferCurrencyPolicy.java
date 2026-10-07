package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Locale;
import java.util.Map;

public final class TransferCurrencyPolicy {
    private static final Map<String, Integer> SCALES = Map.of("MXN", 2, "USD", 2, "JPY", 0);
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999999.9999");

    private TransferCurrencyPolicy() {
    }

    public static String normalizeCurrency(String code) {
        if (code == null) {
            throw new TransferException(TransferErrorCode.INVALID_CURRENCY);
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        try {
            Currency.getInstance(normalized);
        } catch (IllegalArgumentException exception) {
            throw new TransferException(TransferErrorCode.INVALID_CURRENCY);
        }
        if (!SCALES.containsKey(normalized)) {
            throw new TransferException(TransferErrorCode.INVALID_CURRENCY);
        }
        return normalized;
    }

    public static BigDecimal normalizeAmount(BigDecimal amount, String currencyCode) {
        String currency = normalizeCurrency(currencyCode);
        if (amount == null || amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new TransferException(TransferErrorCode.INVALID_AMOUNT);
        }
        try {
            return amount.setScale(SCALES.get(currency), RoundingMode.UNNECESSARY)
                    .setScale(4, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new TransferException(TransferErrorCode.INVALID_AMOUNT);
        }
    }
}
