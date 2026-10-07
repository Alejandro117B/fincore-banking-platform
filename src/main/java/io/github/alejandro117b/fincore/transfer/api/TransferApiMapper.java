package io.github.alejandro117b.fincore.transfer.api;

import java.math.BigDecimal;
import io.github.alejandro117b.fincore.api.ApiInputs;
import io.github.alejandro117b.fincore.transfer.TransferCommand;
import io.github.alejandro117b.fincore.transfer.TransferResult;

public final class TransferApiMapper {
    private TransferApiMapper() {
    }

    public static TransferCommand command(CreateTransferRequest request, String key) {
        // Bean Validation and strict JSON run first. Parse directly from decimal text.
        return new TransferCommand(request.sourceAccountId(), request.destinationAccountId(),
                new BigDecimal(request.amount()), request.currencyCode(), key, request.reference());
    }

    public static TransferResponse from(TransferResult result) {
        return new TransferResponse(result.id(), result.sourceAccountId(), result.destinationAccountId(),
                ApiInputs.money(result.amount()), result.currencyCode(), result.reference(),
                result.journalTransactionId(), result.createdAt(), result.completedAt());
    }
}
