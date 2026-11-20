package com.freightflow.notification.settings;

import com.freightflow.notification.notify.NotificationKind;
import com.freightflow.notification.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/** {@code notification_settings}, one row per organisation that saved settings. */
@Component
public class SettingsStore {

    private final JdbcTemplate jdbcTemplate;

    public SettingsStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public NotificationSettings find(UUID organizationId) {
        return jdbcTemplate.query("""
                SELECT organization_id, language, recipients, kinds, version, updated_at
                FROM notification_settings WHERE organization_id = ?
                """, SettingsStore::map, organizationId).stream().findFirst()
                .orElseGet(() -> NotificationSettings.defaults(organizationId));
    }

    /**
     * Saves the settings if {@code expectedVersion} is the current one (0: none saved yet). Returns the new version,
     * or empty if another change came first.
     */
    public Optional<Long> save(UUID organizationId, String language, List<String> recipients,
                               List<NotificationKind> kinds, long expectedVersion, Actor actor, Instant now) {
        String[] kindCodes = kinds.stream().map(NotificationKind::code).toArray(String[]::new);
        String[] addresses = recipients.toArray(String[]::new);
        int updated;
        if (expectedVersion == 0) {
            updated = jdbcTemplate.update("""
                    INSERT INTO notification_settings (organization_id, language, recipients, kinds, version, updated_at,
                                                       updated_by_subject, updated_by_client_id, update_request_id)
                    VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?)
                    ON CONFLICT (organization_id) DO NOTHING
                    """, organizationId, language, addresses, kindCodes, Timestamp.from(now), actor.subject(),
                    actor.clientId(), actor.requestId());
        } else {
            updated = jdbcTemplate.update("""
                    UPDATE notification_settings
                    SET language = ?, recipients = ?, kinds = ?, version = version + 1, updated_at = ?,
                        updated_by_subject = ?, updated_by_client_id = ?, update_request_id = ?
                    WHERE organization_id = ? AND version = ?
                    """, language, addresses, kindCodes, Timestamp.from(now), actor.subject(), actor.clientId(),
                    actor.requestId(), organizationId, expectedVersion);
        }
        return updated == 1 ? Optional.of(expectedVersion + 1) : Optional.empty();
    }

    private static NotificationSettings map(ResultSet rs, int row) throws SQLException {
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new NotificationSettings(rs.getObject("organization_id", UUID.class), rs.getString("language"),
                List.of(strings(rs.getArray("recipients"))),
                Arrays.stream(strings(rs.getArray("kinds"))).map(NotificationKind::of).flatMap(Optional::stream)
                        .collect(Collectors.toSet()),
                rs.getLong("version"), updatedAt == null ? null : updatedAt.toInstant());
    }

    private static String[] strings(Array array) throws SQLException {
        return array == null ? new String[0] : (String[]) array.getArray();
    }
}
