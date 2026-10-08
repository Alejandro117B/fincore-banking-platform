package io.github.alejandro117b.fincore.dev.demo;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "FINCORE_DEMO_SEED_ENABLED=false")
@ActiveProfiles("dev")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoSeedDisabledIntegrationTests {
    private static final String SCHEMA = "demo_disabled_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry registry) {
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
        registry.add("spring.datasource.hikari.connection-init-sql", () -> "SET search_path TO " + SCHEMA);
    }

    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private Flyway flyway;

    @Test
    void devProfileWithDisabledPropertyMigratesOnlyMetadataAndCreatesNoFunds() {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).isEqualTo("fincore_dev");
        assertThat(jdbc.queryForObject("SELECT current_schema()", String.class)).isEqualTo(SCHEMA);
        assertThat(context.getEnvironment().getProperty("fincore.demo.seed.enabled", Boolean.class)).isFalse();
        assertThat(context.getBeansOfType(StarterDemoSeeder.class)).isEmpty();
        assertThat(context.containsBean("demoSeedRunner")).isFalse();
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/dev-migration");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_seed_runs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM accounts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM journal_transactions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM auth_users", Integer.class)).isZero();
        assertThat(context.getBeansOfType(DemoUsersSeeder.class)).isEmpty();
    }

    @AfterAll
    void dropOnlyTheGeneratedSchema() {
        if (!SCHEMA.matches("demo_disabled_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe test schema name"); }
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
}
