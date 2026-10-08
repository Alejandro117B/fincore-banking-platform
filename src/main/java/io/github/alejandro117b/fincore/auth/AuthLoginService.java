package io.github.alejandro117b.fincore.auth;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuthLoginService {
    private final AuthenticationManager authentication;
    private final AuthUserRepository users;
    private final JwtTokenService tokens;

    public AuthLoginService(AuthenticationManager authentication, AuthUserRepository users, JwtTokenService tokens) {
        this.authentication = authentication;
        this.users = users;
        this.tokens = tokens;
    }

    @Transactional(readOnly = true)
    public String login(String email, String password) {
        authentication.authenticate(UsernamePasswordAuthenticationToken.unauthenticated(email, password));
        var user = users.findByLoginEmail(AuthUser.normalizeEmail(email))
                .orElseThrow(() -> new BadCredentialsException("Invalid credentials"));
        return tokens.issue(user);
    }
}
