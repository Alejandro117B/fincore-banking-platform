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

@SpringBootTest(properties = "fincore.demo.seed.enabled=true")
@ActiveProfiles("prod")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DemoSeedIsolationIntegrationTests {
    private static final String SCHEMA = "demo_isolation_test_" + UUID.randomUUID().toString().replace("-", "");

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
    void prodIgnoresEnabledPropertyAndDoesNotLoadTheDemoMigration() {
        assertThat(context.getBeansOfType(StarterDemoSeeder.class)).isEmpty();
        assertThat(context.containsBean("demoSeedRunner")).isFalse();
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                .containsExactly("classpath:db/migration");
        assertThat(jdbc.queryForObject("SELECT to_regclass('demo_seed_runs')", String.class)).isNull();
        assertThat(jdbc.queryForList("SELECT script FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank", String.class))
                .hasSize(7).noneMatch(script -> script.contains("demo"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ledger_entries", Integer.class)).isZero();
    }

    @AfterAll
    void dropOnlyTheGeneratedSchema() {
        if (!SCHEMA.matches("demo_isolation_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe test schema name"); }
        jdbc.execute("DROP SCHEMA \"" + SCHEMA + "\" CASCADE");
    }
}
