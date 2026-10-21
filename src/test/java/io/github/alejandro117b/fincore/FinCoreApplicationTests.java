package io.github.alejandro117b.fincore;

import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;

import io.github.alejandro117b.fincore.account.Account;
import io.github.alejandro117b.fincore.customer.Customer;
import io.github.alejandro117b.fincore.ledger.JournalTransaction;
import io.github.alejandro117b.fincore.ledger.LedgerAccount;
import io.github.alejandro117b.fincore.ledger.LedgerEntry;
import io.github.alejandro117b.fincore.transfer.Transfer;
import io.github.alejandro117b.fincore.transfer.TransferIdempotencyRecord;
import io.github.alejandro117b.fincore.support.IsolatedPostgresSchema;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(FinCoreApplicationTests.SchemaMigration.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class FinCoreApplicationTests {
    private static final String SCHEMA = "application_test_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedSchema(DynamicPropertyRegistry properties) {
        IsolatedPostgresSchema.register(properties, SCHEMA);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SchemaMigration {
        @Bean
        FlywayMigrationStrategy migrateOnlyThisSchema() {
            return flyway -> IsolatedPostgresSchema.migrate(flyway, SCHEMA);
        }
    }

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private Flyway flyway;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ApplicationContext context;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void flywayJpaJdbcAndDatabaseGuardsUseOnlyThisRunsSchema() throws Exception {
        assertThat(flyway.getConfiguration().getSchemas()).containsExactly(SCHEMA);
        assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo(SCHEMA);
        assertThat(entityManagerFactory.getProperties()).containsEntry("hibernate.default_schema", SCHEMA);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getSchema()).isEqualTo(SCHEMA);
        }
        assertThat(jdbcTemplate.queryForList("SELECT unnest(current_schemas(false))", String.class))
                .containsExactly(SCHEMA);
        assertThat(entityManager.createNativeQuery("SELECT current_schema()", String.class).getSingleResult())
                .isEqualTo(SCHEMA);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT table_schema FROM information_schema.tables
                WHERE table_schema = ? AND table_name = 'flyway_schema_history'
                """, String.class, SCHEMA)).isEqualTo(SCHEMA);
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT target_namespace.nspname
                FROM pg_constraint c
                JOIN pg_namespace owner_namespace ON owner_namespace.oid = c.connamespace
                JOIN pg_class target ON target.oid = c.confrelid
                JOIN pg_namespace target_namespace ON target_namespace.oid = target.relnamespace
                WHERE owner_namespace.nspname = ? AND c.contype = 'f'
                """, String.class, SCHEMA)).containsExactly(SCHEMA);
        assertThat(jdbcTemplate.queryForList("""
                SELECT DISTINCT function_namespace.nspname
                FROM pg_trigger t JOIN pg_class r ON r.oid = t.tgrelid
                JOIN pg_namespace table_namespace ON table_namespace.oid = r.relnamespace
                JOIN pg_proc p ON p.oid = t.tgfoid
                JOIN pg_namespace function_namespace ON function_namespace.oid = p.pronamespace
                WHERE table_namespace.nspname = ? AND NOT t.tgisinternal
                """, String.class, SCHEMA)).containsExactly(SCHEMA);
    }

    @Test
    void noActiveProfileUsesTheRealDefaultDatabaseAndOnlyNormalMigrations() throws Exception {
        assertThat(context.getEnvironment().getActiveProfiles()).isEmpty();
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.getCatalog()).isEqualTo("fincore");
            assertThat(connection.getMetaData().getURL()).isEqualTo(context.getEnvironment().getProperty("spring.datasource.url"));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class)).isEqualTo("fincore");
        assertThat(flyway.getConfiguration().getLocations()).extracting(Object::toString)
                .containsExactly("classpath:db/migration");
    }

    @Test
    void defaultDoesNotRequireDemoMetadataOrRegisterTheSeeder() {
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass(?)", String.class, SCHEMA + ".demo_seed_runs")).isNull();
        assertThat(context.containsBean("starterDemoSeeder")).isFalse();
        assertThat(context.containsBean("demoSeedRunner")).isFalse();
        flyway.validate();
    }

    @Test
    @Transactional
    void jpaAndHibernateInitializeAgainstPostgresql() {
        assertThat(entityManagerFactory.isOpen()).isTrue();
        assertThat(entityManagerFactory.unwrap(SessionFactory.class).isOpen()).isTrue();
        assertThat(entityManagerFactory.getProperties())
                .containsEntry("hibernate.hbm2ddl.auto", "validate");
        assertThat(entityManager.isOpen()).isTrue();
        assertThat(entityManager.createNativeQuery("select version()").getSingleResult().toString())
                .startsWith("PostgreSQL ");
        assertThat(entityManager.getMetamodel().getEntities())
                .extracting(entity -> entity.getJavaType().getName())
                .containsExactlyInAnyOrder(Customer.class.getName(), Account.class.getName(),
                        LedgerAccount.class.getName(), JournalTransaction.class.getName(), LedgerEntry.class.getName(),
                        Transfer.class.getName(), TransferIdempotencyRecord.class.getName(),
                        io.github.alejandro117b.fincore.auth.AuthUser.class.getName());
    }

    @Test
    void flywayAppliedV1ThroughV9Successfully() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true and version is not null order by installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "select column_name, data_type, is_nullable from information_schema.columns "
                        + "where table_schema = ? and table_name = 'infrastructure_probe' "
                        + "order by ordinal_position", SCHEMA))
                .containsExactly(
                        Map.of("column_name", "id", "data_type", "uuid", "is_nullable", "NO"),
                        Map.of("column_name", "marker", "data_type", "character varying", "is_nullable", "NO"));
    }

    @Test
    @Transactional
    void technicalTableSupportsJdbcRoundTrip() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into infrastructure_probe (id, marker) values (?, ?)",
                id, "connectivity-check");
        assertThat(jdbcTemplate.queryForObject(
                "select marker from infrastructure_probe where id = ?", String.class, id))
                .isEqualTo("connectivity-check");
        // Spring rolls this transaction back after the test.
    }

    @AfterAll
    void removeOnlyThisRunsSchema() {
        IsolatedPostgresSchema.drop(jdbcTemplate, SCHEMA);
    }
}
