package io.github.alejandro117b.fincore.auth;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.Set;
import java.util.UUID;
import io.github.alejandro117b.fincore.customer.CustomerRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trusted operator/development entry point. Never exposed as an HTTP registration API. */
@Service
public class AuthUserProvisioningService {
    private final AuthUserRepository users;
    private final CustomerRepository customers;
    private final PasswordEncoder passwords;
    private final Clock clock;

    public AuthUserProvisioningService(AuthUserRepository users, CustomerRepository customers,
                                      PasswordEncoder passwords, Clock clock) {
        this.users = users;
        this.customers = customers;
        this.passwords = passwords;
        this.clock = clock;
    }

    @Transactional
    public UUID provision(String email, String password, UUID customerId, Set<AuthRole> roles) {
        String login = AuthUser.normalizeEmail(email);
        if (password == null || password.isBlank() || password.length() < 12 || password.length() > 128) {
            throw new IllegalArgumentException("A password of 12 to 128 characters is required");
        }
        if (roles == null || roles.isEmpty() || (roles.contains(AuthRole.USER) && customerId == null)) {
            throw new IllegalArgumentException("USER requires an explicitly associated Customer");
        }
        if (customerId != null && !customers.existsById(customerId)) {
            throw new IllegalArgumentException("Associated Customer does not exist");
        }
        if (users.findByLoginEmail(login).isPresent()
                || (customerId != null && users.findByCustomerId(customerId).isPresent())) {
            throw new IllegalStateException("Identity already exists; provisioning never replaces credentials");
        }
        return users.saveAndFlush(AuthUser.create(login, passwords.encode(password), customerId, roles,
                clock.instant().truncatedTo(ChronoUnit.MICROS))).getId();
    }
}
