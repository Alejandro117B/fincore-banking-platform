package io.github.alejandro117b.fincore.auth;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import io.github.alejandro117b.fincore.security.CurrentActor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class JwtTokenService {
    public static final long ACCESS_TOKEN_SECONDS = 900;
    private final JwtEncoder encoder;
    private final Clock clock;
    private final String issuer;
    private final String audience;

    public JwtTokenService(JwtEncoder encoder, Clock clock, Environment env) {
        this.encoder = encoder;
        this.clock = clock;
        this.issuer = env.getRequiredProperty("fincore.security.jwt.issuer");
        this.audience = env.getRequiredProperty("fincore.security.jwt.audience");
    }

    public String issue(AuthUser user) {
        if (user.getStatus() != AuthStatus.ACTIVE) { throw new BadCredentialsException("Invalid credentials"); }
        new CurrentActor(user.getId(), user.getCustomerId(), user.getRoles());
        var now = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        var claims = JwtClaimsSet.builder().subject(user.getId().toString()).issuer(issuer)
                .audience(java.util.List.of(audience)).issuedAt(now).notBefore(now)
                .expiresAt(now.plusSeconds(ACCESS_TOKEN_SECONDS)).id(UUID.randomUUID().toString())
                .claim("ver", user.getSecurityVersion()).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(SignatureAlgorithm.RS256).type("JWT").build(), claims))
                .getTokenValue();
    }
}
