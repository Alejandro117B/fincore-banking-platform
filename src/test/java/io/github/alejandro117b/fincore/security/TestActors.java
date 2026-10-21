package io.github.alejandro117b.fincore.security;

import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import io.github.alejandro117b.fincore.auth.AuthRole;
import io.github.alejandro117b.fincore.auth.AuthUserProvisioningService;
import io.github.alejandro117b.fincore.auth.AuthUserRepository;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

/** Explicit per-thread identities for application-service tests; HTTP tests use real JWTs. */
public final class TestActors {
    private TestActors() { }

    public static CurrentActor provision(UUID customer, AuthUserProvisioningService provisioning, AuthUserRepository users) {
        var user = users.findByCustomerId(customer).orElseGet(() -> users.findById(provisioning.provision(
                customer + "@service.example.test", "service-test-password", customer, Set.of(AuthRole.USER))).orElseThrow());
        return new CurrentActor(user.getId(), user.getCustomerId(), user.getRoles());
    }

    public static <T> T call(CurrentActor actor, Supplier<T> action) {
        var previous = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null,
                actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList()));
        SecurityContextHolder.setContext(context);
        try { return action.get(); }
        finally { SecurityContextHolder.setContext(previous); }
    }
}
