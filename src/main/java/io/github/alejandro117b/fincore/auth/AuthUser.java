package io.github.alejandro117b.fincore.auth;

import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import jakarta.persistence.*;

@Entity
@Table(name = "auth_users")
public class AuthUser {
    @Id @Column(nullable = false, updatable = false) private UUID id;
    @Column(name = "login_email", nullable = false, unique = true, length = 254) private String loginEmail;
    @Column(name = "password_hash", nullable = false, length = 512) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 20) private AuthStatus status;
    @Column(name = "customer_id", unique = true, updatable = false) private UUID customerId;
    @Column(name = "security_version", nullable = false) private long securityVersion;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(nullable = false) private Long version;
    @ElementCollection
    @CollectionTable(name = "auth_user_roles", joinColumns = @JoinColumn(name = "auth_user_id"))
    @Enumerated(EnumType.STRING) @Column(name = "role", nullable = false, length = 20)
    private Set<AuthRole> roles = new HashSet<>();

    protected AuthUser() { }

    public static AuthUser create(String email, String hash, UUID customerId, Set<AuthRole> roles, Instant at) {
        Objects.requireNonNull(at, "Creation time is required");
        if (roles == null || roles.isEmpty() || roles.stream().anyMatch(Objects::isNull)
                || (roles.contains(AuthRole.USER) && customerId == null)) {
            throw new IllegalArgumentException("Roles are required; USER must have an explicitly associated Customer");
        }
        if (hash == null || !hash.startsWith("{argon2id}$argon2id$v=19$")) {
            throw new IllegalArgumentException("An Argon2id password hash is required");
        }
        AuthUser user = new AuthUser();
        user.id = UUID.randomUUID();
        user.loginEmail = normalizeEmail(email);
        user.passwordHash = hash;
        user.customerId = customerId;
        user.roles = new HashSet<>(roles);
        user.status = AuthStatus.ACTIVE;
        user.createdAt = at;
        user.updatedAt = at;
        return user;
    }

    public static String normalizeEmail(String email) {
        if (email == null) { throw new IllegalArgumentException("Login email is required"); }
        String normalized = email.strip().toLowerCase(Locale.ROOT);
        if (normalized.length() > 254 || !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("Login email is invalid");
        }
        return normalized;
    }

    public void disable(Instant at) {
        revokeTokens(at);
        status = AuthStatus.DISABLED;
    }

    public void revokeTokens(Instant at) {
        if (at == null || at.isBefore(updatedAt)) { throw new IllegalArgumentException("Invalid update time"); }
        securityVersion = Math.addExact(securityVersion, 1);
        updatedAt = at;
    }

    public UUID getId() { return id; }
    public String getLoginEmail() { return loginEmail; }
    public String getPasswordHash() { return passwordHash; }
    public AuthStatus getStatus() { return status; }
    public UUID getCustomerId() { return customerId; }
    public long getSecurityVersion() { return securityVersion; }
    public Set<AuthRole> getRoles() { return Set.copyOf(roles); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public Long getVersion() { return version; }
}
