package io.github.alejandro117b.fincore.security;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class JwtConfigurationTests {
    private final WebApplicationContextRunner context = new WebApplicationContextRunner()
            .withUserConfiguration(JwtConfiguration.class).withBean(Clock.class, Clock::systemUTC);

    @ParameterizedTest
    @ValueSource(strings = {"", "classpath:private.pem", "https://example.test/private.pem", "file:/missing-fincore-test-key.pem"})
    void missingOrNonExternalPrivateKeysFailClosed(String location) {
        context.withPropertyValues("fincore.security.jwt.private-key=" + location).run(result -> {
            assertThat(result).hasFailed();
            assertThat(result.getStartupFailure()).hasRootCauseMessage(
                    "Valid external RSA JWT keys are required (PKCS8 private, X509 public)");
        });
    }

    @Test
    void missingIssuerFailsClosed() {
        context.withPropertyValues("fincore.security.jwt.issuer=").run(result -> assertThat(result).hasFailed());
    }
}
