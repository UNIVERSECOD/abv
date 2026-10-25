package com.hic.config;

import org.springframework.boot.autoconfigure.flyway.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.sql.Connection;
import java.sql.SQLException;

/** Select the immutable V043 resource matching successful history, without repair. */
@Configuration
public class LeaveMigrationLineageConfiguration {
    static final String MAIN_SCRIPT = "V043__track_leave_balance_charges.sql";
    static final String OPENING_SCRIPT = "V043__leave_balance_opening_date.sql";
    static final int MAIN_CHECKSUM = 911121862;
    static final int OPENING_CHECKSUM = 666281236;
    static final String MAIN_LOCATION = "classpath:db/lineage/main";
    static final String OPENING_LOCATION = "classpath:db/lineage/opening";

    @Bean
    FlywayConfigurationCustomizer leaveMigrationLineage() {
        return configuration -> {
            try (Connection connection = configuration.getDataSource().getConnection()) {
                String schema = configuration.getDefaultSchema();
                if (schema == null && configuration.getSchemas().length > 0) schema = configuration.getSchemas()[0];
                if (schema == null) schema = connection.getSchema();
                String table = configuration.getTable();
                String location = MAIN_LOCATION;
                try (var tables = connection.getMetaData().getTables(null, schema, table, new String[]{"TABLE"})) {
                    if (tables.next()) {
                        try (var statement = connection.prepareStatement("SELECT script, checksum FROM "
                                + quote(schema) + "." + quote(table)
                                + " WHERE version IN ('043', '43') AND success = TRUE");
                             var history = statement.executeQuery()) {
                            if (history.next()) {
                                location = locationFor(history.getString(1), (Integer) history.getObject(2));
                                if (history.next()) throw new IllegalStateException("Multiple successful V043 migrations");
                            }
                        }
                    }
                }
                var placeholders = new java.util.HashMap<>(configuration.getPlaceholders());
                placeholders.put("leaveAccountingLineage", MAIN_LOCATION.equals(location) ? "main" : "opening");
                configuration.placeholders(placeholders);
                configuration.locations("classpath:db/migration", location);
            } catch (SQLException exception) {
                throw new IllegalStateException("Cannot safely determine leave migration lineage", exception);
            }
        };
    }

    static String locationFor(String script, Integer checksum) {
        // These are the actual released Flyway checksums; ordinary Flyway validation
        // additionally checks the selected resource's contents before migration.
        if (MAIN_SCRIPT.equals(script) && Integer.valueOf(MAIN_CHECKSUM).equals(checksum)) return MAIN_LOCATION;
        if (OPENING_SCRIPT.equals(script) && Integer.valueOf(OPENING_CHECKSUM).equals(checksum)) return OPENING_LOCATION;
        throw new IllegalStateException("Unknown or modified V043 migration; automatic repair is disabled");
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }
}
