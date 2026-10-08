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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class FinCoreApplicationTests {

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
        assertThat(jdbcTemplate.queryForObject("SELECT to_regclass('public.demo_seed_runs')", String.class)).isNull();
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
                        Transfer.class.getName(), TransferIdempotencyRecord.class.getName());
    }

    @Test
    void flywayAppliedV1ThroughV7Successfully() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("7");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbcTemplate.queryForList(
                "select version from flyway_schema_history where success = true order by installed_rank",
                String.class)).containsExactly("1", "2", "3", "4", "5", "6", "7");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from flyway_schema_history where version = '1' and success = true",
                Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "select column_name, data_type, is_nullable from information_schema.columns "
                        + "where table_schema = 'public' and table_name = 'infrastructure_probe' "
                        + "order by ordinal_position"))
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
}
