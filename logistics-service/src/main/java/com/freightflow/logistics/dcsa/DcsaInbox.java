package com.freightflow.logistics.dcsa;

import com.freightflow.logistics.outbox.OutboxClaims;
import com.freightflow.logistics.telemetry.TraceContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The carrier events received (ADR 0037): stored as sent, once per DCSA eventID, then processed. Pushed and polled
 * events land here alike, so both get the same deduplication and the same out-of-order handling downstream.
 */
@Repository
public class DcsaInbox {

    static final OutboxClaims.Table TABLE = OutboxClaims.Table.unordered("dcsa_events", "{row}.status = 'PENDING'");

    private final JdbcTemplate jdbcTemplate;
    private final OutboxClaims claims;
    private final TraceContext traces;
    private final Clock clock;

    public DcsaInbox(JdbcTemplate jdbcTemplate, OutboxClaims claims, TraceContext traces, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.claims = claims;
        this.traces = traces;
        this.clock = clock;
    }

    /** A DCSA event as read from its JSON: the fields the inbox needs, the rest kept in the payload. */
    public record Received(UUID eventId, String eventType, Instant createdAt, String equipmentReference,
                           JsonNode payload) {

        public static Received of(JsonNode event) {
            UUID id;
            Instant created;
            try {
                id = UUID.fromString(event.path("eventID").asString(""));
                created = OffsetDateTime.parse(event.path("eventCreatedDateTime").asString("")).toInstant();
            } catch (IllegalArgumentException | DateTimeParseException ex) {
                throw new DcsaExceptions.MalformedEventException("an event needs an eventID (UUID) and an "
                        + "eventCreatedDateTime (ISO 8601 with offset): " + ex.getMessage());
            }
            String type = event.path("eventType").asString("").toUpperCase(Locale.ROOT);
            if (!List.of("EQUIPMENT", "TRANSPORT", "SHIPMENT").contains(type)) {
                throw new DcsaExceptions.MalformedEventException("eventType must be EQUIPMENT, TRANSPORT or SHIPMENT, "
                        + "not '" + type + "'");
            }
            String equipment = event.path("equipmentReference").asString("");
            return new Received(id, type, created, equipment.isBlank() ? null : equipment.trim().toUpperCase(Locale.ROOT),
                    event);
        }
    }

    /** Stores the event unless its eventID is known; true when it was new. */
    @Transactional
    public boolean store(String carrierId, UUID subscriptionId, String channel, Received event) {
        Instant now = clock.instant();
        return jdbcTemplate.update("""
                INSERT INTO dcsa_events (id, carrier_id, subscription_id, channel, event_type, event_created_at,
                                         equipment_reference, payload, received_at, status, next_attempt_at, traceparent)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', ?, ?)
                ON CONFLICT (id) DO NOTHING
                """, event.eventId(), carrierId, subscriptionId, channel, event.eventType(),
                Timestamp.from(event.createdAt()), event.equipmentReference(), event.payload().toString(),
                Timestamp.from(now), Timestamp.from(now), traces.current()) == 1;
    }

    public record Pending(UUID id, String carrierId, String equipmentReference, String payload, int attempts,
                          String traceparent) {
    }

    public List<UUID> claimDue(int limit, Duration lease) {
        return claims.claim(TABLE, limit, lease, "id", (rs, row) -> rs.getObject("id", UUID.class));
    }

    public Pending load(UUID id) {
        return jdbcTemplate.queryForObject("""
                SELECT id, carrier_id, equipment_reference, payload::text AS payload, attempts, traceparent
                FROM dcsa_events WHERE id = ?
                """, (rs, row) -> new Pending(rs.getObject("id", UUID.class), rs.getString("carrier_id"),
                rs.getString("equipment_reference"), rs.getString("payload"), rs.getInt("attempts"),
                rs.getString("traceparent")), id);
    }

    @Transactional
    public void finish(UUID id, String status, UUID shipmentId, String outcome) {
        Instant now = clock.instant();
        jdbcTemplate.update("""
                UPDATE dcsa_events SET status = ?, shipment_id = ?, outcome = ?, processed_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, status, shipmentId, outcome.length() > 300 ? outcome.substring(0, 300) : outcome,
                Timestamp.from(now), id);
    }

    @Transactional
    public void retryAt(UUID id, Instant at, String outcome) {
        jdbcTemplate.update("UPDATE dcsa_events SET next_attempt_at = ?, outcome = ? WHERE id = ? AND status = 'PENDING'",
                Timestamp.from(at), outcome.length() > 300 ? outcome.substring(0, 300) : outcome, id);
    }
}
