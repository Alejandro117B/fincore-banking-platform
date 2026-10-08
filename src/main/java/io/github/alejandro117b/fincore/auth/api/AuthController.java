package io.github.alejandro117b.fincore.auth.api;

import io.github.alejandro117b.fincore.auth.AuthLoginService;
import io.github.alejandro117b.fincore.auth.JwtTokenService;
import io.github.alejandro117b.fincore.security.CurrentActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping(value = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Authentication")
public class AuthController {
    private final AuthLoginService login;
    public AuthController(AuthLoginService login) { this.login = login; }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Authenticate with email and password")
    public ResponseEntity<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        String token = login.login(request.email(), request.password());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new LoginResponse(token, "Bearer", JwtTokenService.ACCESS_TOKEN_SECONDS));
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current identity")
    public IdentityResponse me() {
        var actor = CurrentActor.require();
        return new IdentityResponse(actor.userId(), actor.customerId(), actor.roles());
    }
}
