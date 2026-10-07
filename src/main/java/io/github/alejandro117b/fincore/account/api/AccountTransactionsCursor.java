package io.github.alejandro117b.fincore.account.api;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import io.github.alejandro117b.fincore.api.ApiException;
import io.github.alejandro117b.fincore.api.ApiInputs;
import org.springframework.http.HttpStatus;

public record AccountTransactionsCursor(UUID accountId, Instant upperAt, UUID upperId, Instant afterAt, UUID afterId) {
    public String encode() {
        String value = "1|" + accountId + "|" + upperAt + "|" + upperId + "|" + afterAt + "|" + afterId;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    public static AccountTransactionsCursor decode(String token, UUID accountId) {
        try {
            if (token == null || token.isBlank() || token.length() > 512) { throw invalid(); }
            String[] parts = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 6 || !parts[0].equals("1")) { throw invalid(); }
            AccountTransactionsCursor cursor = new AccountTransactionsCursor(ApiInputs.uuid(parts[1]),
                    instant(parts[2]), ApiInputs.uuid(parts[3]), instant(parts[4]), ApiInputs.uuid(parts[5]));
            if (!cursor.accountId().equals(accountId) || !cursor.encode().equals(token)
                    || cursor.afterAt().isAfter(cursor.upperAt())
                    || (cursor.afterAt().equals(cursor.upperAt())
                        && cursor.afterId().toString().compareTo(cursor.upperId().toString()) > 0)) {
                throw invalid();
            }
            return cursor;
        } catch (IllegalArgumentException | DateTimeException | ApiException exception) {
            throw invalid();
        }
    }

    private static Instant instant(String value) {
        // Bound untrusted cursors before converting to JDBC timestamps; persisted clocks use microseconds.
        if (!value.matches("[0-9]{4}-.*Z")) { throw invalid(); }
        Instant instant = Instant.parse(value);
        if (instant.getNano() % 1000 != 0 || instant.isBefore(Instant.parse("0001-01-01T00:00:00Z"))) { throw invalid(); }
        return instant;
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CURSOR", "Cursor is invalid for this account.");
    }
}
