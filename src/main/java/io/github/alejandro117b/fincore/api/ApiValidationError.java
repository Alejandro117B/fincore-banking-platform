package io.github.alejandro117b.fincore.api;

public record ApiValidationError(String field, String code, String message) {
}
