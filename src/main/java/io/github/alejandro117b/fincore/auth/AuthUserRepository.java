package io.github.alejandro117b.fincore.auth;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuthUserRepository extends JpaRepository<AuthUser, UUID> {
    @Override @EntityGraph(attributePaths = "roles") Optional<AuthUser> findById(UUID id);
    @EntityGraph(attributePaths = "roles") Optional<AuthUser> findByLoginEmail(String email);
    @EntityGraph(attributePaths = "roles") Optional<AuthUser> findByCustomerId(UUID customerId);
}
