package io.github.alejandro117b.fincore.auth;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import static org.assertj.core.api.Assertions.*;

class AuthDomainTests {
    private final PasswordEncoder passwords = new PasswordConfiguration().passwordEncoder();
    private static final Instant AT = Instant.parse("2026-10-08T12:00:00Z");

    @Test
    void argon2idUsesDistinctSaltsAndMatchesOnlyTheExactPassword() {
        String password = "  exact password with spaces  ";
        String first = passwords.encode(password);
        String second = passwords.encode(password);
        assertThat(first).startsWith("{argon2id}$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(second);
        assertThat(passwords.matches(password, first)).isTrue();
        assertThat(passwords.matches(password, second)).isTrue();
        assertThat(passwords.matches(password.strip(), first)).isFalse();
        assertThat(passwords.matches("incorrect password", first)).isFalse();
    }

    @Test
    void userRequiresAnExplicitCustomerAndAdminMayHaveNone() {
        String hash = passwords.encode("domain-test-password");
        assertThatThrownBy(() -> AuthUser.create("user@example.test", hash, null, Set.of(AuthRole.USER), AT))
                .isInstanceOf(IllegalArgumentException.class);
        var admin = AuthUser.create(" ADMIN@Example.Test ", hash, null, Set.of(AuthRole.ADMIN), AT);
        assertThat(admin.getLoginEmail()).isEqualTo("admin@example.test");
        assertThat(admin.getCustomerId()).isNull();
        assertThat(admin.getRoles()).containsExactly(AuthRole.ADMIN);
    }

    @Test
    void rawPasswordsAndMissingRolesCannotBecomeIdentities() {
        assertThatThrownBy(() -> AuthUser.create("user@example.test", "plaintext", UUID.randomUUID(), Set.of(AuthRole.USER), AT))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AuthUser.create("user@example.test", passwords.encode("domain-test-password"), null, Set.of(), AT))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disablingRevokesTokensAndRolesAreImmutableToCallers() {
        var user = AuthUser.create("user@example.test", passwords.encode("domain-test-password"),
                UUID.randomUUID(), Set.of(AuthRole.USER), AT);
        user.disable(AT.plusSeconds(1));
        assertThat(user.getStatus()).isEqualTo(AuthStatus.DISABLED);
        assertThat(user.getSecurityVersion()).isEqualTo(1);
        assertThat(user.getUpdatedAt()).isEqualTo(AT.plusSeconds(1));
        assertThatThrownBy(() -> user.getRoles().add(AuthRole.ADMIN)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void credentialDtosDoNotExposeSecretsThroughToString() {
        assertThat(new io.github.alejandro117b.fincore.auth.api.LoginRequest("user@example.test", "secret password").toString())
                .doesNotContain("secret password", "user@example.test");
        assertThat(new io.github.alejandro117b.fincore.auth.api.LoginResponse("secret.jwt.token", "Bearer", 900).toString())
                .doesNotContain("secret.jwt.token");
    }
}
