package io.github.alejandro117b.fincore.transfer;

import java.math.BigDecimal;
import java.util.UUID;

/** Application input, independent of any transport. Validation happens before reservation. */
public record TransferCommand(UUID sourceAccountId, UUID destinationAccountId, BigDecimal amount,
                              String currencyCode, String idempotencyKey, String reference) {
}
