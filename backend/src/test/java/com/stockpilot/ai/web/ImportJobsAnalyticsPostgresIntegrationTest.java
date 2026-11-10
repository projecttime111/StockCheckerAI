package com.stockpilot.ai.web;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.sql.DriverManager;
import java.util.UUID;

/** Verify durable job recovery, XLSX reconciliation and analytics coverage against PostgreSQL without touching shared application data. */
@EnabledIfEnvironmentVariable(named = "STOCKPILOT_AUDIT_PG_URL", matches = "jdbc:postgresql://127\\.0\\.0\\.1:55434/stockpilot_audit")
@ActiveProfiles({"test", "audit-postgres"})
@Import(LaunchAuditPostgresIntegrationTest.DatabaseGuard.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ImportJobsAnalyticsPostgresIntegrationTest extends ImportJobsAnalyticsIntegrationTest {
    private static final String AUDIT_URL = "jdbc:postgresql://127.0.0.1:55434/stockpilot_audit";
    private static final String SCHEMA = "audit_import_jobs_" + UUID.randomUUID().toString().replace("-", "");

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> AUDIT_URL);
        properties.add("spring.datasource.username", () -> "audit");
        properties.add("spring.datasource.password", () -> "");
        properties.add("spring.datasource.hikari.schema", () -> SCHEMA);
        properties.add("spring.flyway.url", () -> AUDIT_URL);
        properties.add("spring.flyway.user", () -> "audit");
        properties.add("spring.flyway.password", () -> "");
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
    }

    @AfterAll
    static void removeOnlyThisRunSchema() throws Exception {
        try (var connection = DriverManager.getConnection(AUDIT_URL, "audit", ""); var statement = connection.createStatement()) {
            if (!AUDIT_URL.equals(connection.getMetaData().getURL()) || !"audit".equals(connection.getMetaData().getUserName())) {
                throw new IllegalStateException("Refusing cleanup outside the disposable audit database");
            }
            // SCHEMA is generated above, never an environment value or a customer-supplied identifier.
            statement.execute("drop schema if exists " + SCHEMA + " cascade");
        }
    }
}
