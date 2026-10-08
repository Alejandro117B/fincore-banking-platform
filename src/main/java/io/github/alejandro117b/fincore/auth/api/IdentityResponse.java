package io.github.alejandro117b.fincore.auth.api;

import java.util.Set;
import java.util.UUID;
import io.github.alejandro117b.fincore.auth.AuthRole;

public record IdentityResponse(UUID id, UUID customerId, Set<AuthRole> roles) { }
