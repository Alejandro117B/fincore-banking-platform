package io.github.alejandro117b.fincore.dev.demo;

import java.time.Clock;

import io.github.alejandro117b.fincore.account.AccountService;
import io.github.alejandro117b.fincore.customer.CustomerService;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerPostingService;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration(proxyBeanMethods = false)
@Profile("dev")
@ConditionalOnProperty(name = "fincore.demo.seed.enabled", havingValue = "true", matchIfMissing = false)
public class DemoSeedConfiguration {
    private static final Logger LOG = LoggerFactory.getLogger(DemoSeedConfiguration.class);

    @Bean
    StarterDemoSeeder starterDemoSeeder(PlatformTransactionManager manager, JdbcTemplate jdbc,
                                        EntityManager em, CustomerService customers, AccountService accounts,
                                        LedgerAccountRepository ledgers, LedgerPostingService posting, Clock clock) {
        return new StarterDemoSeeder(manager, jdbc, em, customers, accounts, ledgers, posting, clock);
    }

    @Bean
    ApplicationRunner demoSeedRunner(StarterDemoSeeder seeder, DemoUsersSeeder users) {
        return arguments -> {
            // seed() returns only after commit, including deferred ledger checks.
            var result = seeder.seed();
            var identities = users.seed(result);
            LOG.info("Demo USER identities ready: Alejandro ID={}, Fernando ID={}",
                    identities.alejandroUserId(), identities.fernandoUserId());
            LOG.info("Demo scenario {} ready. Alejandro Demo: Customer ID={}, Account ID={}, "
                            + "expected initial balance=2000.0000 MXN. Fernando Demo: Customer ID={}, Account ID={}. "
                            + "Funding Journal ID={}. Existing funds are never replenished.",
                    StarterDemoSeeder.SCENARIO_KEY, result.alejandroCustomerId(), result.alejandroAccountId(),
                    result.fernandoCustomerId(), result.fernandoAccountId(), result.fundingJournalId());
        };
    }
}
