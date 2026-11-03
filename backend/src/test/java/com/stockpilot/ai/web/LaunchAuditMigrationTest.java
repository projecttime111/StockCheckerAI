package com.stockpilot.ai.web;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.sql.DriverManager;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "STOCKPILOT_AUDIT_PG_URL",
    matches = "jdbc:postgresql://127\\.0\\.0\\.1:55434/stockpilot_audit")
class LaunchAuditMigrationTest {
    @Test
    void upgradesV22AccountWithoutChangingItsCredentialsAndSupportsRepeatMigration() throws Exception {
        var url = System.getenv("STOCKPILOT_AUDIT_PG_URL");
        var schema = "audit_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        Flyway.configure().dataSource(url, "audit", "").schemas(schema).defaultSchema(schema).target("22").load().migrate();
        try (var connection = DriverManager.getConnection(url, "audit", ""); var statement = connection.createStatement()) {
            statement.execute("set search_path to " + schema);
            statement.execute("insert into app_users (id,email,password_hash,full_name,created_at,updated_at) values ('"
                + UUID.randomUUID() + "','upgrade@example.com','unchanged-hash','Upgrade test',now(),now())");
            var flyway = Flyway.configure().dataSource(url, "audit", "").schemas(schema).defaultSchema(schema).load();
            flyway.migrate();
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            try (var rows = statement.executeQuery("select password_hash,auth_version from app_users where email='upgrade@example.com'")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getString(1)).isEqualTo("unchanged-hash");
                assertThat(rows.getLong(2)).isZero();
            }
        }
    }
}
