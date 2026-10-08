package io.github.alejandro117b.fincore.dev.demo;

import java.util.Set;
import java.util.UUID;
import io.github.alejandro117b.fincore.auth.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Identity provisioning is separate from the immutable starter-mxn-v1 financial definition. */
@Service
@Profile("dev")
@ConditionalOnProperty(name = "fincore.demo.seed.enabled", havingValue = "true", matchIfMissing = false)
public class DemoUsersSeeder {
    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final AuthUserRepository users;
    private final AuthUserProvisioningService provisioning;
    private final Environment environment;

    public DemoUsersSeeder(PlatformTransactionManager manager, JdbcTemplate jdbc, AuthUserRepository users,
                           AuthUserProvisioningService provisioning, Environment environment) {
        this.transactions = new TransactionTemplate(manager);
        transactions.setTimeout(30);
        this.jdbc = jdbc;
        this.users = users;
        this.provisioning = provisioning;
        this.environment = environment;
    }

    public record UserIds(UUID alejandroUserId, UUID fernandoUserId) { }

    public UserIds seed(StarterDemoSeeder.SeedIds ids) {
        return transactions.execute(status -> {
            jdbc.execute("SET LOCAL lock_timeout = '10s'");
            jdbc.query("SELECT pg_advisory_xact_lock(117, 20261008)", rs -> { rs.next(); return null; });
            return new UserIds(identity(ids.alejandroCustomerId(), "alejandro"),
                    identity(ids.fernandoCustomerId(), "fernando"));
        });
    }

    private UUID identity(UUID customerId, String name) {
        String prefix = "fincore.demo.seed." + name;
        String login = AuthUser.normalizeEmail(environment.getRequiredProperty(prefix + "-email"));
        var existing = users.findByCustomerId(customerId);
        if (existing.isPresent()) {
            var user = existing.orElseThrow();
            if (!user.getLoginEmail().equals(login) || !user.getRoles().equals(Set.of(AuthRole.USER))) {
                throw new IllegalStateException("Demo identity association is incompatible; credentials were not changed");
            }
            return user.getId();
        }
        return provisioning.provision(login, environment.getProperty(prefix + "-password"), customerId, Set.of(AuthRole.USER));
    }
}
