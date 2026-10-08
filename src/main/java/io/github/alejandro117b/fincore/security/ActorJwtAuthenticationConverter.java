package io.github.alejandro117b.fincore.security;

import java.util.UUID;
import io.github.alejandro117b.fincore.auth.AuthIdentityService;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;

public class ActorJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final AuthIdentityService identities;

    public ActorJwtAuthenticationConverter(AuthIdentityService identities) { this.identities = identities; }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        CurrentActor actor;
        try { actor = identities.loadActor(UUID.fromString(jwt.getSubject()), JwtConfiguration.tokenVersion(jwt)); }
        catch (IllegalArgumentException ex) { throw new InvalidBearerTokenException("Invalid token"); }
        var authorities = actor.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        // No password or bearer token is retained in the application principal or credentials.
        return UsernamePasswordAuthenticationToken.authenticated(actor, null, authorities);
    }
}
