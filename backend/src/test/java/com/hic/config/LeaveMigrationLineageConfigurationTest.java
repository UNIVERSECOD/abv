package com.hic.config;

import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static com.hic.config.LeaveMigrationLineageConfiguration.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class LeaveMigrationLineageConfigurationTest {
    @Test void knownReleasedChecksumsSelectExactlyTheirOriginalResources() {
        assertThat(locationFor(MAIN_SCRIPT, MAIN_CHECKSUM)).isEqualTo(MAIN_LOCATION);
        assertThat(locationFor(OPENING_SCRIPT, OPENING_CHECKSUM)).isEqualTo(OPENING_LOCATION);
    }

    @Test void unknownOrModifiedHistoryCannotBeSilentlyRepaired() {
        assertThatThrownBy(() -> locationFor("V043__unknown.sql", MAIN_CHECKSUM)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> locationFor(MAIN_SCRIPT, OPENING_CHECKSUM)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> locationFor(OPENING_SCRIPT, null)).isInstanceOf(IllegalStateException.class);
    }

    @Test void freshDatabaseUsesMainAndItsTrustedMigrationPlaceholder() throws Exception {
        var configuration = configuration(false, null, null);
        new LeaveMigrationLineageConfiguration().leaveMigrationLineage().customize(configuration);
        verify(configuration).locations("classpath:db/migration", MAIN_LOCATION);
        verify(configuration).placeholders(argThat(values -> "main".equals(values.get("leaveAccountingLineage"))));
    }

    @Test void existingOpeningDatabaseKeepsItsOriginalHistoryAndIgnoresUntrustedPlaceholder() throws Exception {
        var configuration = configuration(true, OPENING_SCRIPT, OPENING_CHECKSUM);
        new LeaveMigrationLineageConfiguration().leaveMigrationLineage().customize(configuration);
        verify(configuration).locations("classpath:db/migration", OPENING_LOCATION);
        verify(configuration).placeholders(argThat(values -> "opening".equals(values.get("leaveAccountingLineage"))
                && "kept".equals(values.get("other"))));
    }

    @Test void databaseReadFailureStopsMigrationRatherThanGuessingALineage() throws Exception {
        DataSource source = mock(DataSource.class);
        when(source.getConnection()).thenThrow(new SQLException("unavailable"));
        var configuration = mock(FluentConfiguration.class);
        when(configuration.getDataSource()).thenReturn(source);
        assertThatThrownBy(() -> new LeaveMigrationLineageConfiguration().leaveMigrationLineage()
                .customize(configuration))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("safely determine");
    }

    private FluentConfiguration configuration(boolean historyPresent, String script, Integer checksum) throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet tables = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getSchema()).thenReturn("public");
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(isNull(), eq("public"), eq("flyway_schema_history_backend"), any())).thenReturn(tables);
        when(tables.next()).thenReturn(historyPresent);
        if (historyPresent) {
            PreparedStatement statement = mock(PreparedStatement.class);
            ResultSet history = mock(ResultSet.class);
            when(connection.prepareStatement(contains("'043'"))).thenReturn(statement);
            when(statement.executeQuery()).thenReturn(history);
            when(history.next()).thenReturn(true, false);
            when(history.getString(1)).thenReturn(script);
            when(history.getObject(2)).thenReturn(checksum);
        }
        var configuration = mock(FluentConfiguration.class);
        when(configuration.getDataSource()).thenReturn(source);
        when(configuration.getSchemas()).thenReturn(new String[0]);
        when(configuration.getTable()).thenReturn("flyway_schema_history_backend");
        when(configuration.getPlaceholders()).thenReturn(java.util.Map.of("leaveAccountingLineage", "main", "other", "kept"));
        return configuration;
    }
}
