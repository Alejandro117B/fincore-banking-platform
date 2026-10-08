package io.github.alejandro117b.fincore.auth;

import java.util.UUID;
import io.github.alejandro117b.fincore.security.CurrentActor;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthIdentityService implements UserDetailsService {
    private final AuthUserRepository users;

    public AuthIdentityService(AuthUserRepository users) { this.users = users; }

    @Override @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) {
        String login;
        try { login = AuthUser.normalizeEmail(email); }
        catch (IllegalArgumentException ex) { throw new UsernameNotFoundException("Invalid credentials"); }
        AuthUser user = users.findByLoginEmail(login).orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        return User.withUsername(user.getLoginEmail()).password(user.getPasswordHash())
                .disabled(user.getStatus() != AuthStatus.ACTIVE)
                .authorities(user.getRoles().stream().map(role -> "ROLE_" + role.name()).toArray(String[]::new)).build();
    }

    @Transactional(readOnly = true)
    public CurrentActor loadActor(UUID userId, long tokenVersion) {
        AuthUser user = users.findById(userId).orElseThrow(() -> new InvalidBearerTokenException("Invalid token"));
        if (user.getStatus() != AuthStatus.ACTIVE || user.getSecurityVersion() != tokenVersion) {
            throw new InvalidBearerTokenException("Invalid token");
        }
        try { return new CurrentActor(user.getId(), user.getCustomerId(), user.getRoles()); }
        catch (IllegalArgumentException ex) { throw new InvalidBearerTokenException("Invalid token"); }
    }
}
