package io.github.alejandro117b.fincore.security;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtConfiguration {
    @Bean
    RSAKey signingKey(Environment env, ResourceLoader resources) {
        try {
            String privateLocation = env.getRequiredProperty("fincore.security.jwt.private-key");
            String publicLocation = env.getRequiredProperty("fincore.security.jwt.public-key");
            if (!privateLocation.startsWith("file:") || !publicLocation.startsWith("file:")) {
                throw new IllegalArgumentException("JWT keys must be external file: resources");
            }
            var factory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey;
            RSAPublicKey publicKey;
            try (var input = resources.getResource(privateLocation).getInputStream()) {
                privateKey = (RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(
                        pem(input.readAllBytes(), "PRIVATE KEY")));
            }
            try (var input = resources.getResource(publicLocation).getInputStream()) {
                publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(
                        pem(input.readAllBytes(), "PUBLIC KEY")));
            }
            if (!privateKey.getModulus().equals(publicKey.getModulus()) || publicKey.getModulus().bitLength() < 2048) {
                throw new IllegalArgumentException("JWT RSA keys must match and be at least 2048 bits");
            }
            String keyId = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(publicKey.getEncoded()));
            return new RSAKey.Builder(publicKey).privateKey(privateKey).keyID(keyId).build();
        } catch (Exception ex) {
            // Never include configuration values or key material in diagnostics.
            throw new IllegalStateException("Valid external RSA JWT keys are required (PKCS8 private, X509 public)");
        }
    }

    private byte[] pem(byte[] bytes, String type) {
        String text = new String(bytes, StandardCharsets.US_ASCII);
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        if (!text.startsWith(begin) || !text.stripTrailing().endsWith(end)) {
            throw new IllegalArgumentException("Invalid PEM format");
        }
        return Base64.getDecoder().decode(text.replace(begin, "").replace(end, "").replaceAll("\\s", ""));
    }

    @Bean
    JwtEncoder jwtEncoder(RSAKey key) {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(RSAKey key, Environment env, Clock clock) throws Exception {
        String issuer = env.getRequiredProperty("fincore.security.jwt.issuer");
        String audience = env.getRequiredProperty("fincore.security.jwt.audience");
        if (issuer.isBlank() || audience.isBlank()) { throw new IllegalStateException("JWT issuer and audience are required"); }
        var decoder = NimbusJwtDecoder.withPublicKey(key.toRSAPublicKey()).signatureAlgorithm(SignatureAlgorithm.RS256).build();
        var timestamps = new JwtTimestampValidator(Duration.ofSeconds(30));
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> claims = jwt -> {
            try {
                var now = clock.instant();
                UUIDChecks.requireUuid(jwt.getSubject());
                UUIDChecks.requireUuid(jwt.getId());
                long version = tokenVersion(jwt);
                boolean valid = version >= 0 && jwt.getAudience().contains(audience)
                        && jwt.getIssuedAt() != null && jwt.getNotBefore() != null && jwt.getExpiresAt() != null
                        && !jwt.getIssuedAt().isAfter(now.plusSeconds(30))
                        && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                        && !jwt.getNotBefore().isAfter(jwt.getExpiresAt());
                if (valid) { return OAuth2TokenValidatorResult.success(); }
            } catch (RuntimeException ignored) { }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid token", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamps, new JwtIssuerValidator(issuer), claims));
        return decoder;
    }

    static long tokenVersion(Jwt jwt) {
        Object value = jwt.getClaims().get("ver");
        if (!(value instanceof Number)) { throw new IllegalArgumentException("Invalid version claim"); }
        return new BigDecimal(value.toString()).longValueExact();
    }

    private static class UUIDChecks {
        static void requireUuid(String value) {
            if (value == null || !java.util.UUID.fromString(value).toString().equals(value)) {
                throw new IllegalArgumentException("Invalid identifier claim");
            }
        }
    }
}
