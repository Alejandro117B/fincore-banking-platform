package io.github.alejandro117b.fincore.transfer;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/** Global protocol: all Account locks first, then all LedgerAccount locks, in canonical UUID order. */
public final class TransferLockOrder {
    private static final Comparator<UUID> ORDER = Comparator.comparing(UUID::toString);

    private TransferLockOrder() {
    }

    public static List<UUID> ordered(UUID first, UUID second) {
        return Stream.of(first, second).distinct().sorted(ORDER).toList();
    }
}
