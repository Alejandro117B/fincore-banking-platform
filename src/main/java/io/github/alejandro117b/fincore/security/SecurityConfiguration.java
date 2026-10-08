package io.github.alejandro117b.fincore.security;

import io.github.alejandro117b.fincore.api.ApiErrorResponses;
import io.github.alejandro117b.fincore.auth.AuthIdentityService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

@Configuration(proxyBeanMethods = false)
@org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SecurityConfiguration {
    @Bean
    AuthenticationManager authenticationManager(AuthIdentityService identities, PasswordEncoder passwords) {
        var provider = new DaoAuthenticationProvider(identities);
        provider.setPasswordEncoder(passwords);
        return new ProviderManager(provider);
    }

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, AuthIdentityService identities,
                                   ApiErrorResponses errors, Environment env) throws Exception {
        AuthenticationEntryPoint missing = (request, response, exception) -> errors.write(request, response,
                HttpStatus.UNAUTHORIZED, "AUTHENTICATION_REQUIRED", "Authentication is required.", true);
        AuthenticationEntryPoint invalid = (request, response, exception) -> errors.write(request, response,
                HttpStatus.UNAUTHORIZED, "INVALID_TOKEN", "Bearer token is invalid or expired.", true);
        AccessDeniedHandler denied = (request, response, exception) -> errors.write(request, response,
                HttpStatus.FORBIDDEN, "FORBIDDEN", "Access is forbidden.", false);
        http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()).formLogin(form -> form.disable()).httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable()).requestCache(cache -> cache.disable())
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(missing).accessDeniedHandler(denied))
                .authorizeHttpRequests(routes -> {
                    routes.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/auth/login").permitAll();
                    routes.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/auth/me").authenticated();
                    routes.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/customers", "/api/v1/accounts").hasRole("ADMIN");
                    routes.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/customers/*",
                            "/api/v1/accounts/*", "/api/v1/accounts/*/balance", "/api/v1/accounts/*/transactions").hasRole("USER");
                    // Phase 7A: authentication only. Ownership and idempotency remain explicitly pending for 7B.
                    routes.requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/transfers").authenticated();
                    routes.requestMatchers(org.springframework.http.HttpMethod.GET, "/api/v1/transfers/*").authenticated();
                    if (env.acceptsProfiles(Profiles.of("dev"))) {
                        routes.requestMatchers(org.springframework.http.HttpMethod.GET, "/v3/api-docs", "/v3/api-docs/**",
                                "/swagger-ui.html", "/swagger-ui/**").permitAll();
                    }
                    routes.anyRequest().denyAll();
                })
                .oauth2ResourceServer(resource -> resource.authenticationEntryPoint(invalid).accessDeniedHandler(denied)
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(new ActorJwtAuthenticationConverter(identities))));
        return http.build();
    }
}
