package io.github.alejandro117b.fincore.security;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import io.github.alejandro117b.fincore.auth.AuthRole;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

public record CurrentActor(UUID userId, UUID customerId, Set<AuthRole> roles) {
    public CurrentActor {
        Objects.requireNonNull(userId);
        roles = Set.copyOf(roles);
        if (roles.isEmpty() || (roles.contains(AuthRole.USER) && customerId == null)) {
            throw new IllegalArgumentException("Invalid authenticated identity association");
        }
    }

    public static CurrentActor require() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof CurrentActor actor)) {
            throw new AuthenticationCredentialsNotFoundException("Authentication is required");
        }
        return actor;
    }
}
