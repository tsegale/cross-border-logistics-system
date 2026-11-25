package com.freightflow.notification.support;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service runs as notification_app, not as the owner (ADR 0041): it reads and writes rows, never the schema; the
 * collector's monitoring role reads statistics only.
 */
class DatabaseRolesIntegrationTest extends IntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theServiceIsItsRuntimeRoleWithoutAnyPowerOverTheSchema() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class)).isEqualTo("notification_app");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolbypassrls FROM pg_roles WHERE rolname = current_user",
                Boolean.class)).isFalse();
        assertThatThrownBy(() -> jdbcTemplate.execute("CREATE TABLE intruder (id int)"))
                .hasStackTraceContaining("permission denied");
        assertThatThrownBy(() -> jdbcTemplate.execute("ALTER TABLE notifications ADD COLUMN intruder int"))
                .hasStackTraceContaining("must be owner");
        assertThatThrownBy(() -> jdbcTemplate.queryForList("SELECT * FROM flyway_schema_history"))
                .hasStackTraceContaining("permission denied");
    }

    @Test
    void monitoringReadsStatisticsAndNoRows() {
        assertThat(jdbcTemplate.queryForObject("SELECT pg_has_role('monitoring', 'pg_monitor', 'MEMBER')",
                Boolean.class)).isTrue();
        assertThat(privileges("monitoring", "notifications")).isEmpty();
        assertThat(privileges("monitoring", "notification_settings")).isEmpty();
    }

    private List<String> privileges(String role, String table) {
        return jdbcTemplate.queryForList("""
                SELECT privilege_type FROM (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) AS p(privilege_type)
                WHERE has_table_privilege(?, ?, privilege_type)
                """, String.class, role, table);
    }
}
