package io.github.alejandro117b.fincore.dev.demo;

import java.time.Clock;

import io.github.alejandro117b.fincore.account.AccountService;
import io.github.alejandro117b.fincore.customer.CustomerService;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerPostingService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DemoSeedConfigurationTests {
    private final CustomerService customers = mock(CustomerService.class);
    private final AccountService accounts = mock(AccountService.class);
    private final LedgerPostingService posting = mock(LedgerPostingService.class);
    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(DemoSeedConfiguration.class)
            .withBean(DemoUsersSeeder.class, () -> mock(DemoUsersSeeder.class))
            .withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(EntityManager.class, () -> mock(EntityManager.class))
            .withBean(CustomerService.class, () -> customers)
            .withBean(AccountService.class, () -> accounts)
            .withBean(LedgerAccountRepository.class, () -> mock(LedgerAccountRepository.class))
            .withBean(LedgerPostingService.class, () -> posting)
            .withBean(Clock.class, Clock::systemUTC);

    @Test
    void devWithoutPropertyIsDisabledByDefault() {
        context.withPropertyValues("spring.profiles.active=dev").run(this::assertDisabled);
    }

    @Test
    void explicitlyDisabledInDevDoesNotExecute() {
        context.withPropertyValues("spring.profiles.active=dev", "fincore.demo.seed.enabled=false")
                .run(this::assertDisabled);
    }

    @Test
    void enabledPropertyWithoutDevProfileDoesNotExecute() {
        context.withPropertyValues("fincore.demo.seed.enabled=true").run(this::assertDisabled);
    }

    @Test
    void enabledPropertyInProdDoesNotExecute() {
        context.withPropertyValues("spring.profiles.active=prod", "fincore.demo.seed.enabled=true")
                .run(this::assertDisabled);
    }

    @Test
    void onlyDevAndTrueRegisterTheSeederAndRunner() {
        context.withPropertyValues("spring.profiles.active=dev", "fincore.demo.seed.enabled=true").run(result -> {
            assertThat(result).hasSingleBean(StarterDemoSeeder.class).hasBean("demoSeedRunner");
        });
    }

    private void assertDisabled(org.springframework.boot.test.context.assertj.AssertableApplicationContext result) {
        assertThat(result).doesNotHaveBean(StarterDemoSeeder.class).doesNotHaveBean("demoSeedRunner");
        verifyNoInteractions(customers, accounts, posting);
    }
}
