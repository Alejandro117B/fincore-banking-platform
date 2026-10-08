package io.github.alejandro117b.fincore.auth.provisioning;

import java.util.Arrays;
import java.util.Set;
import io.github.alejandro117b.fincore.auth.AuthRole;
import io.github.alejandro117b.fincore.auth.AuthUserProvisioningService;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnNotWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/** Explicit non-HTTP operator mode. Requires an interactive console; no passwords in CLI arguments. */
@Configuration(proxyBeanMethods = false)
@Profile("provision-admin")
@ConditionalOnNotWebApplication
public class AdminProvisioningConfiguration {
    @Bean
    ApplicationRunner provisionAdmin(AuthUserProvisioningService provisioning, Environment environment) {
        return arguments -> {
            var console = System.console();
            if (console == null) { throw new IllegalStateException("ADMIN provisioning requires an interactive console"); }
            String email = environment.getRequiredProperty("FINCORE_ADMIN_EMAIL");
            char[] password = console.readPassword("New ADMIN password (12-128 characters): ");
            char[] confirmation = console.readPassword("Confirm password: ");
            try {
                if (password == null || confirmation == null || !Arrays.equals(password, confirmation)) {
                    throw new IllegalArgumentException("Password confirmation does not match");
                }
                var id = provisioning.provision(email, new String(password), null, Set.of(AuthRole.ADMIN));
                console.printf("ADMIN provisioned. AuthUser ID=%s%n", id);
            } finally {
                if (password != null) { Arrays.fill(password, '\0'); }
                if (confirmation != null) { Arrays.fill(confirmation, '\0'); }
            }
        };
    }
}
