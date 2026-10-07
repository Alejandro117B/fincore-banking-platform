package io.github.alejandro117b.fincore.api;

import org.springframework.http.HttpStatus;

/** Controlled adapter/application failure. Never wrap unexpected technical exceptions here. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;

    public ApiException(HttpStatus status, String code, String safeMessage) {
        super(safeMessage);
        this.status = status;
        this.code = code;
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
}
