package io.github.alejandro117b.fincore.auth.api;

public record LoginResponse(String accessToken, String tokenType, long expiresIn) {
    @Override public String toString() { return "LoginResponse[token redacted]"; }
}
