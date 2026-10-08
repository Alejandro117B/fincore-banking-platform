package io.github.alejandro117b.fincore.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

public record LoginRequest(@NotBlank @Size(max = 254) String email,
        @NotBlank @Size(max = 128) @Schema(accessMode = Schema.AccessMode.WRITE_ONLY) String password) {
    @Override public String toString() { return "LoginRequest[credentials redacted]"; }
}
