package io.github.alejandro117b.fincore.auth;

import io.github.alejandro117b.fincore.auth.provisioning.AdminProvisioningConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AdminProvisioningConfigurationTests {
    @Test
    void operatorModeRequiresAnExplicitProfile() {
        new ApplicationContextRunner().withUserConfiguration(AdminProvisioningConfiguration.class)
                .withBean(AuthUserProvisioningService.class, () -> mock(AuthUserProvisioningService.class))
                .run(context -> assertThat(context).doesNotHaveBean("provisionAdmin"));
    }

    @Test
    void operatorRunnerIsNeverRegisteredInTheHttpServer() {
        new WebApplicationContextRunner().withUserConfiguration(AdminProvisioningConfiguration.class)
                .withPropertyValues("spring.profiles.active=provision-admin")
                .withBean(AuthUserProvisioningService.class, () -> mock(AuthUserProvisioningService.class))
                .run(context -> assertThat(context).doesNotHaveBean("provisionAdmin"));
    }

    @Test
    void explicitNonHttpOperatorModeRegistersProvisioning() {
        new ApplicationContextRunner().withUserConfiguration(AdminProvisioningConfiguration.class)
                .withPropertyValues("spring.profiles.active=provision-admin")
                .withBean(AuthUserProvisioningService.class, () -> mock(AuthUserProvisioningService.class))
                .run(context -> assertThat(context).hasBean("provisionAdmin"));
    }
}
