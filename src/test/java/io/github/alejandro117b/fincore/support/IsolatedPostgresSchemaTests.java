package io.github.alejandro117b.fincore.support;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.Configuration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class IsolatedPostgresSchemaTests {
    private static final String SCHEMA = "application_test_" + "a".repeat(32);

    private Flyway configured(String defaultSchema, String... schemas) {
        var flyway = mock(Flyway.class);
        var configuration = mock(Configuration.class);
        when(flyway.getConfiguration()).thenReturn(configuration);
        when(configuration.getDefaultSchema()).thenReturn(defaultSchema);
        when(configuration.getSchemas()).thenReturn(schemas);
        return flyway;
    }

    @Test
    void rejectsPublicAsDefaultBeforeConnectingOrMigrating() {
        var flyway = configured("public", SCHEMA);
        assertThatThrownBy(() -> IsolatedPostgresSchema.migrate(flyway, SCHEMA))
                .isInstanceOf(IllegalStateException.class);
        verify(flyway.getConfiguration(), never()).getDataSource();
        verify(flyway, never()).migrate();
    }

    @Test
    void rejectsAnyAdditionalFlywaySchemaBeforeConnectingOrMigrating() {
        var flyway = configured(SCHEMA, SCHEMA, "public");
        assertThatThrownBy(() -> IsolatedPostgresSchema.migrate(flyway, SCHEMA))
                .isInstanceOf(IllegalStateException.class);
        verify(flyway.getConfiguration(), never()).getDataSource();
        verify(flyway, never()).migrate();
    }

    @Test
    void rejectsPublicSearchPathBeforeMigrating() throws Exception {
        var flyway = configured(SCHEMA, SCHEMA);
        var source = mock(DataSource.class);
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        var rows = mock(ResultSet.class);
        when(flyway.getConfiguration().getDataSource()).thenReturn(source);
        when(source.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT current_setting('search_path')")).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getString(1)).thenReturn("public");
        assertThatThrownBy(() -> IsolatedPostgresSchema.migrate(flyway, SCHEMA))
                .isInstanceOf(IllegalStateException.class);
        verify(flyway, never()).migrate();
    }

    @ParameterizedTest
    @ValueSource(strings = {"public", "application_test_not_generated"})
    void cleanupCannotTargetPublicOrAnUnownedSchema(String schema) {
        var jdbc = mock(JdbcTemplate.class);
        assertThatThrownBy(() -> IsolatedPostgresSchema.drop(jdbc, schema))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(jdbc);
    }
}
