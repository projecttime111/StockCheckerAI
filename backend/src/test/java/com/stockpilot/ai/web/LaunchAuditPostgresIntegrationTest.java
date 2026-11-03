package com.stockpilot.ai.web;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

// Deliberately restricted to the disposable local audit cluster, never the app database.
@EnabledIfEnvironmentVariable(named = "STOCKPILOT_AUDIT_PG_URL",
    matches = "jdbc:postgresql://127\\.0\\.0\\.1:55434/stockpilot_audit")
@ActiveProfiles({"test", "audit-postgres"})
@Import(LaunchAuditPostgresIntegrationTest.DatabaseGuard.class)
class LaunchAuditPostgresIntegrationTest extends LaunchAuditRegressionTest {
    static final String AUDIT_URL = "jdbc:postgresql://127.0.0.1:55434/stockpilot_audit";

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        // Override both connections even if the developer environment contains database credentials.
        properties.add("spring.datasource.url", () -> AUDIT_URL);
        properties.add("spring.datasource.username", () -> "audit");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.flyway.url", () -> AUDIT_URL);
        properties.add("spring.flyway.user", () -> "audit");
        properties.add("spring.flyway.password", () -> "");
    }

    @TestConfiguration
    static class DatabaseGuard {
        @Bean
        FlywayMigrationStrategy auditMigrationStrategy() {
            return flyway -> {
                try (var connection = flyway.getConfiguration().getDataSource().getConnection()) {
                    if (!AUDIT_URL.equals(connection.getMetaData().getURL())
                        || !"audit".equals(connection.getMetaData().getUserName())) {
                        throw new IllegalStateException("Refusing to migrate outside the audit database");
                    }
                } catch (java.sql.SQLException ex) {
                    throw new IllegalStateException("Cannot verify audit database", ex);
                }
                flyway.migrate();
            };
        }
    }
}
