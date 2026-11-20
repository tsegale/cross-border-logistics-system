package com.freightflow.logistics.deadletter;

import com.freightflow.logistics.common.UuidV7;
import com.freightflow.logistics.security.Actor;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * logistics' dead letters, kept in its database for operators (ADR 0026): the record, where it came from, why it
 * failed, and who redrove it (ADR 0020).
 */
@Component
public class DeadLetterStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record DeadLetter(UUID id, String originalTopic, int partition, long offset, String key, String value,
                             UUID eventId, String eventType, String error, Instant receivedAt, String status,
                             int redriveAttempts, String lastRedriveError, Instant redrivenAt,
                             String redrivenBySubject, String redrivenByClientId) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public DeadLetterStore(JdbcTemplate jdbcTemplate, MeterRegistry meters, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        Gauge.builder("freightflow.dead-letters", this, DeadLetterStore::countNew)
                .description("Dead letters waiting for an operator")
                .tag("status", "NEW")
                .register(meters);
    }

    /**
     * Stores a record that failed for good; true if it is new. Recovering the same record again (the error handler
     * retries a recovery that failed to publish) changes nothing.
     */
    public boolean save(ConsumerRecord<?, ?> record, String error) {
        String value = record.value() == null ? "" : record.value().toString();
        JsonNode parsed = tryParse(value);
        UUID eventId = parsed == null ? null : uuid(parsed.path("eventId").asString(null));
        String eventType = parsed == null ? null : parsed.path("type").asString(null);
        return jdbcTemplate.update("""
                INSERT INTO dead_letters (id, original_topic, record_partition, record_offset, record_key,
                                          record_value, event_id, event_type, error, received_at, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'NEW')
                ON CONFLICT (original_topic, record_partition, record_offset) DO NOTHING
                """, UuidV7.next(clock), record.topic(), record.partition(), record.offset(),
                truncate(record.key() == null ? null : record.key().toString(), 200), value, eventId,
                truncate(eventType, 64), truncate(error == null ? "unknown" : error, 1000),
                Timestamp.from(clock.instant())) == 1;
    }

    public Optional<DeadLetter> find(UUID id) {
        return jdbcTemplate.query("SELECT * FROM dead_letters WHERE id = ?", DeadLetterStore::map, id).stream()
                .findFirst();
    }

    /** Locks the row for a redrive, so two operators cannot redrive it at once. */
    public Optional<DeadLetter> lock(UUID id) {
        return jdbcTemplate.query("SELECT * FROM dead_letters WHERE id = ? FOR UPDATE", DeadLetterStore::map, id)
                .stream().findFirst();
    }

    public List<DeadLetter> list(String status, int limit) {
        return status == null
                ? jdbcTemplate.query("SELECT * FROM dead_letters ORDER BY received_at DESC, id DESC LIMIT ?",
                        DeadLetterStore::map, limit)
                : jdbcTemplate.query("SELECT * FROM dead_letters WHERE status = ? "
                        + "ORDER BY received_at DESC, id DESC LIMIT ?", DeadLetterStore::map, status, limit);
    }

    public void redriven(UUID id, Actor actor) {
        jdbcTemplate.update("""
                UPDATE dead_letters SET status = 'REDRIVEN', redriven_at = ?, redriven_by_subject = ?,
                    redriven_by_client_id = ?, redrive_request_id = ?, redrive_attempts = redrive_attempts + 1
                WHERE id = ?
                """, Timestamp.from(clock.instant()), actor.subject(), actor.clientId(), actor.requestId(), id);
    }

    /** Outside the redrive's transaction: the failure is recorded although the redrive rolled back. */
    public int redriveFailed(UUID id, String error) {
        Integer attempts = jdbcTemplate.queryForObject("""
                UPDATE dead_letters SET redrive_attempts = redrive_attempts + 1, last_redrive_error = ?
                WHERE id = ? RETURNING redrive_attempts
                """, Integer.class, truncate(error, 1000), id);
        return attempts == null ? 0 : attempts;
    }

    public long countNew() {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM dead_letters WHERE status = 'NEW'", Long.class);
        return count == null ? 0 : count;
    }

    private static DeadLetter map(ResultSet rs, int row) throws SQLException {
        Timestamp redrivenAt = rs.getTimestamp("redriven_at");
        return new DeadLetter(rs.getObject("id", UUID.class), rs.getString("original_topic"),
                rs.getInt("record_partition"), rs.getLong("record_offset"), rs.getString("record_key"),
                rs.getString("record_value"), rs.getObject("event_id", UUID.class), rs.getString("event_type"),
                rs.getString("error"), rs.getTimestamp("received_at").toInstant(), rs.getString("status"),
                rs.getInt("redrive_attempts"), rs.getString("last_redrive_error"),
                redrivenAt == null ? null : redrivenAt.toInstant(), rs.getString("redriven_by_subject"),
                rs.getString("redriven_by_client_id"));
    }

    private static JsonNode tryParse(String value) {
        try {
            return JSON.readTree(value);
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    private static UUID uuid(String value) {
        try {
            return value == null ? null : UUID.fromString(value);
        } catch (IllegalArgumentException notAnId) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
