package io.github.alejandro117b.fincore;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import io.github.alejandro117b.fincore.dev.demo.StarterDemoSeeder;
import io.github.alejandro117b.fincore.ledger.LedgerAccountRepository;
import io.github.alejandro117b.fincore.ledger.LedgerBalanceService;
import io.github.alejandro117b.fincore.transfer.TransferCommand;
import io.github.alejandro117b.fincore.transfer.TransferService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Real configuration, JDBC connections and fresh application contexts; no datasource mocks. */
@Timeout(90)
class DatasourceProfileIntegrationTests {
    @Test
    void devWithoutEnabledPropertyUsesItsOwnDatabaseAndDoesNotSeed() throws Exception {
        String schema = schema();
        try (var dev = start("dev", schema, false, false)) {
            try {
                assertDatasource(dev, "fincore_dev", schema);
                assertThat(dev.getEnvironment().getActiveProfiles()).containsExactly("dev");
                assertLocations(dev, true);
                assertThat(dev.getEnvironment().getProperty("fincore.demo.seed.enabled", Boolean.class)).isFalse();
                assertThat(dev.getBeansOfType(StarterDemoSeeder.class)).isEmpty();
                assertThat(dev.containsBean("demoSeedRunner")).isFalse();
                for (String table : List.of("demo_seed_runs", "customers", "accounts", "ledger_entries")) {
                    assertThat(jdbc(dev).queryForObject("SELECT count(*) FROM " + table, Integer.class)).as(table).isZero();
                }
                dev.getBean(Flyway.class).validate();
            } finally {
                dropOnlyTheGeneratedSchema(dev, schema);
            }
        }
    }

    @Test
    void devRestartPreservesSpentFundsAndDefaultStartsAfterDevWithoutMigrationConflict() throws Exception {
        String schema = schema();
        StarterDemoSeeder.SeedIds ids;
        Map<String, String> before;
        // Retain this context until cleanup so every failure path can remove only its own fixture schema.
        try (var dev = start("dev", schema, true, false)) {
            try {
                assertDatasource(dev, "fincore_dev", schema);
                assertLocations(dev, true);
                ids = dev.getBean(StarterDemoSeeder.class).seed();
                assertThat(jdbc(dev).queryForObject("SELECT count(*) FROM demo_seed_runs", Integer.class)).isEqualTo(1);
                dev.getBean(TransferService.class).execute(new TransferCommand(ids.alejandroAccountId(),
                        ids.fernandoAccountId(), new BigDecimal("500.0000"), "MXN", "profile-demo-spend", "profile fixture"));
                assertBalance(dev, ids.alejandroAccountId(), "1500.0000");
                assertBalance(dev, ids.fernandoAccountId(), "500.0000");
                before = snapshot(dev);

                // A fresh application runs its actual startup runner against the persisted scenario.
                // Read-only JDBC connections make any accidental re-funding fail at the database.
                try (var restartedDev = start("dev", schema, true, true)) {
                    assertDatasource(restartedDev, "fincore_dev", schema);
                    assertLocations(restartedDev, true);
                    assertThat(restartedDev.getBean(StarterDemoSeeder.class).seed()).isEqualTo(ids);
                    assertBalance(restartedDev, ids.alejandroAccountId(), "1500.0000");
                    assertBalance(restartedDev, ids.fernandoAccountId(), "500.0000");
                    assertThat(snapshot(restartedDev)).isEqualTo(before);
                }

                // The same schema name in the other database must get a distinct migration history.
                try (var normal = start("", schema, false, false)) {
                    try {
                        assertDatasource(normal, "fincore", schema);
                        assertThat(normal.getEnvironment().getActiveProfiles()).isEmpty();
                        assertLocations(normal, false);
                        assertThat(normal.getBeansOfType(StarterDemoSeeder.class)).isEmpty();
                        assertThat(jdbc(normal).queryForObject("SELECT to_regclass(?)", String.class,
                                schema + ".demo_seed_runs")).isNull();
                        normal.getBean(Flyway.class).validate();
                        assertThat(normal.getBean(Flyway.class).info().pending()).isEmpty();
                        assertThat(jdbc(normal).queryForList("SELECT script FROM flyway_schema_history "
                                + "WHERE version IS NOT NULL ORDER BY installed_rank", String.class))
                                .hasSize(7).noneMatch(script -> script.contains("demo"));
                        assertThat(snapshot(dev)).isEqualTo(before);
                    } finally {
                        dropOnlyTheGeneratedSchema(normal, schema);
                    }
                }
            } finally {
                dropOnlyTheGeneratedSchema(dev, schema);
            }
        }
    }

    private static ConfigurableApplicationContext start(String profile, String schema, boolean seed, boolean readOnly) {
        List<String> arguments = new ArrayList<>(List.of(
                "--spring.profiles.active=" + profile,
                "--POSTGRES_DB=fincore", "--POSTGRES_DEV_DB=fincore_dev",
                "--FINCORE_DEMO_SEED_ENABLED=false",
                "--spring.flyway.schemas=" + schema,
                "--spring.flyway.default-schema=" + schema,
                "--spring.jpa.properties.hibernate.default_schema=" + schema,
                "--spring.datasource.hikari.connection-init-sql=SET search_path TO " + schema,
                "--spring.datasource.hikari.read-only=" + readOnly));
        if (seed) {
            arguments.add("--fincore.demo.seed.enabled=true");
        }
        return new SpringApplicationBuilder(FinCoreApplication.class).web(WebApplicationType.NONE)
                .run(arguments.toArray(String[]::new));
    }

    private static void assertDatasource(ConfigurableApplicationContext context, String database, String schema) throws Exception {
        var environment = context.getEnvironment();
        String expectedUrl = "jdbc:postgresql://" + environment.getProperty("POSTGRES_HOST", "localhost") + ":"
                + environment.getProperty("POSTGRES_PORT", "5432") + "/" + database;
        assertThat(environment.getProperty("spring.datasource.url")).isEqualTo(expectedUrl);
        try (var connection = context.getBean(DataSource.class).getConnection()) {
            assertThat(connection.getMetaData().getURL()).isEqualTo(expectedUrl);
            assertThat(connection.getCatalog()).isEqualTo(database);
        }
        assertThat(jdbc(context).queryForObject("SELECT current_database()", String.class)).isEqualTo(database);
        assertThat(jdbc(context).queryForObject("SELECT current_schema()", String.class)).isEqualTo(schema);
    }

    private static void assertLocations(ConfigurableApplicationContext context, boolean dev) {
        var locations = context.getBean(Flyway.class).getConfiguration().getLocations();
        if (dev) {
            assertThat(locations).extracting(Object::toString)
                    .containsExactlyInAnyOrder("classpath:db/migration", "classpath:db/dev-migration");
        } else {
            assertThat(locations).extracting(Object::toString).containsExactly("classpath:db/migration");
        }
    }

    private static Map<String, String> snapshot(ConfigurableApplicationContext context) {
        Map<String, String> result = new LinkedHashMap<>();
        for (String table : List.of("customers", "accounts", "ledger_accounts", "journal_transactions",
                "ledger_entries", "transfers", "transfer_idempotency_records")) {
            result.put(table, jdbc(context).queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY id), '[]')::text FROM "
                    + table + " t", String.class));
        }
        result.put("demo_seed_runs", jdbc(context).queryForObject("SELECT coalesce(jsonb_agg(to_jsonb(t) ORDER BY scenario_key), '[]')::text "
                + "FROM demo_seed_runs t", String.class));
        result.put("flyway_schema_history", jdbc(context).queryForObject("SELECT jsonb_agg(to_jsonb(t) ORDER BY installed_rank)::text "
                + "FROM flyway_schema_history t", String.class));
        return result;
    }

    private static void assertBalance(ConfigurableApplicationContext context, UUID accountId, String amount) {
        UUID ledgerId = context.getBean(LedgerAccountRepository.class).findByAccountId(accountId).orElseThrow().getId();
        assertThat(context.getBean(LedgerBalanceService.class).getBalance(ledgerId)).isEqualByComparingTo(amount);
    }

    private static JdbcTemplate jdbc(ConfigurableApplicationContext context) {
        return context.getBean(JdbcTemplate.class);
    }

    private static String schema() { return "profile_test_" + UUID.randomUUID().toString().replace("-", ""); }

    private static void dropOnlyTheGeneratedSchema(ConfigurableApplicationContext context, String schema) {
        if (!schema.matches("profile_test_[0-9a-f]{32}")) { throw new IllegalStateException("Unsafe test schema name"); }
        jdbc(context).execute("DROP SCHEMA \"" + schema + "\" CASCADE");
    }
}
