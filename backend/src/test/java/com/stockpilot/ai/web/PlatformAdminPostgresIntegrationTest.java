package com.stockpilot.ai.web;

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Same API/authorization scenarios against migrated PostgreSQL, never a developer/client database. */
@EnabledIfEnvironmentVariable(named="STOCKPILOT_AUDIT_PG_URL",matches="jdbc:postgresql://127\\.0\\.0\\.1:55434/stockpilot_audit")
@ActiveProfiles({"test","audit-postgres"})
@Import(LaunchAuditPostgresIntegrationTest.DatabaseGuard.class)
class PlatformAdminPostgresIntegrationTest extends PlatformAdminIntegrationTest {
    @DynamicPropertySource static void isolatedDatabase(DynamicPropertyRegistry properties) {
        String url="jdbc:postgresql://127.0.0.1:55434/stockpilot_audit";
        properties.add("spring.datasource.url",()->url); properties.add("spring.datasource.username",()->"audit"); properties.add("spring.datasource.password",()->"");
        properties.add("spring.flyway.url",()->url); properties.add("spring.flyway.user",()->"audit"); properties.add("spring.flyway.password",()->"");
    }
}
