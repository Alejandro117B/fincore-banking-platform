package io.github.alejandro117b.fincore.account.api;

import java.util.List;
import io.swagger.v3.oas.annotations.media.Schema;

public record AccountTransactionsResponse(List<AccountTransactionResponse> items, boolean hasMore,
                                          @Schema(nullable = true, description = "Opaque versioned account-bound cursor, or null at the end.") String nextCursor) {
    public AccountTransactionsResponse {
        items = List.copyOf(items);
    }
}
