package io.github.alejandro117b.fincore.transfer;

public class TransferException extends RuntimeException {
    private final TransferErrorCode code;

    public TransferException(TransferErrorCode code) {
        super(code.name());
        this.code = code;
    }

    public TransferErrorCode getCode() {
        return code;
    }
}
