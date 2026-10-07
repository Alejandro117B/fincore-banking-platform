package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

public final class TransferRequestHasher {
    public static final int HASH_VERSION = 1;

    private TransferRequestHasher() {
    }

    public static String hash(UUID source, UUID destination, BigDecimal amount, String currency) {
        String normalizedCurrency = TransferCurrencyPolicy.normalizeCurrency(currency);
        BigDecimal normalizedAmount = TransferCurrencyPolicy.normalizeAmount(amount, normalizedCurrency);
        String canonical = "fincore:internal-transfer:v1\nsourceAccountId=" + source
                + "\ndestinationAccountId=" + destination + "\namount=" + normalizedAmount.toPlainString()
                + "\ncurrencyCode=" + normalizedCurrency + "\n";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 must be available", exception);
        }
    }
}
