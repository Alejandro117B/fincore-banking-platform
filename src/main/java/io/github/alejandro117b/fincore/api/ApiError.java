package io.github.alejandro117b.fincore.api;

import java.time.Instant;
import java.util.List;

public record ApiError(Instant timestamp, int status, String code, String message,
                       String path, String requestId, List<ApiValidationError> details) {
}
