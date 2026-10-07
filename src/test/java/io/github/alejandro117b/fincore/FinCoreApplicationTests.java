package io.github.alejandro117b.fincore;

import java.util.Map;
import java.util.UUID;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceContext;
import org.flywaydb.core.Flyway;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

    @Test
    void contextLoads() {
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
        // Column validation of mapped entities comes with the first real model.
        assertThat(entityManager.getMetamodel().getEntities()).isEmpty();
    }

    @Test
    void flywayAppliedV1Successfully() {
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(flyway.info().pending()).isEmpty();
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
