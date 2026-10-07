package io.github.alejandro117b.fincore.api;

import io.github.alejandro117b.fincore.transfer.TransferErrorCode;
import org.springframework.http.HttpStatus;

public final class TransferHttpErrors {
    private TransferHttpErrors() {
    }

    public static ApiException map(TransferErrorCode code) {
        return switch (code) {
            case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, code, "Invalid transfer request.");
            case INVALID_AMOUNT -> error(HttpStatus.UNPROCESSABLE_CONTENT, code, "Amount is not valid for this transfer.");
            case INVALID_CURRENCY -> error(HttpStatus.UNPROCESSABLE_CONTENT, code, "Currency is not supported for transfers.");
            case ACCOUNT_NOT_FOUND -> error(HttpStatus.NOT_FOUND, code, "A requested account was not found.");
            case LEDGER_ACCOUNT_NOT_FOUND -> new ApiException(HttpStatus.CONFLICT, "ACCOUNT_NOT_READY", "An account is not ready for this operation.");
            case INSUFFICIENT_FUNDS -> error(HttpStatus.CONFLICT, code, "Insufficient funds for this transfer.");
            case ACCOUNT_BLOCKED -> error(HttpStatus.CONFLICT, code, "A requested account is blocked.");
            case ACCOUNT_CLOSED -> error(HttpStatus.CONFLICT, code, "A requested account is closed.");
            case CURRENCY_MISMATCH -> error(HttpStatus.UNPROCESSABLE_CONTENT, code, "Accounts and transfer must use the same currency.");
            case SAME_ACCOUNT -> error(HttpStatus.UNPROCESSABLE_CONTENT, code, "Source and destination must be different accounts.");
            case IDEMPOTENCY_CONFLICT -> error(HttpStatus.CONFLICT, code, "This idempotency key was already used for a different request.");
        };
    }

    private static ApiException error(HttpStatus status, TransferErrorCode code, String message) {
        return new ApiException(status, code.name(), message);
    }
}
