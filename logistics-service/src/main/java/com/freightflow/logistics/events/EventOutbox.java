package com.freightflow.logistics.events;

import com.freightflow.logistics.telemetry.TraceContext;
import com.freightflow.logistics.common.RequestIdFilter;
import com.freightflow.logistics.common.UuidV7;
import com.freightflow.logistics.outbox.OutboxClaims;
import com.freightflow.logistics.security.Actor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The event outbox table (ADR 0025): appending events in the caller's transaction, and claiming, finishing and
 * pruning them for {@link EventRelay}.
 *
 * <p>Order per aggregate is kept by claiming only an aggregate's <em>oldest</em> unpublished event: while it is
 * unpublished, no relay, on this instance or another, can claim the next one.
 */
@Component
public class EventOutbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** One aggregate's events go out in sequence order (ADR 0025). */
    public static final OutboxClaims.Table TABLE = OutboxClaims.Table.orderedBy("event_outbox",
            "{row}.published_at IS NULL", "aggregate_id", "sequence");

    private final JdbcTemplate jdbcTemplate;
    private final OutboxClaims claims;
    private final Clock clock;
    private final TraceContext traces;

    public EventOutbox(JdbcTemplate jdbcTemplate, OutboxClaims claims, Clock clock, TraceContext traces) {
        this.jdbcTemplate = jdbcTemplate;
        this.claims = claims;
        this.clock = clock;
        this.traces = traces;
    }

    /** One claimed event, ready to send. */
    public record PendingEvent(UUID eventId, String topic, String type, UUID aggregateId, long sequence,
                               String envelope, Instant occurredAt, int attempts, String traceparent) {
    }

    /**
     * Appends an event in the caller's transaction, so it commits or rolls back with the change it reports. The
     * aggregate's next sequence number is taken by an upsert that holds the sequence row until commit.
     *
     * @param actor who caused it, or null for a scheduled job
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventEnvelope append(String topic, String type, int schemaVersion, UUID aggregateId, Object payload,
                                Actor actor) {
        Long sequence = jdbcTemplate.queryForObject("""
                INSERT INTO event_sequences (aggregate_id, last_sequence) VALUES (?, 1)
                ON CONFLICT (aggregate_id) DO UPDATE SET last_sequence = event_sequences.last_sequence + 1
                RETURNING last_sequence
                """, Long.class, aggregateId);
        Instant now = clock.instant();
        String requestId = actor != null ? actor.requestId() : MDC.get(RequestIdFilter.MDC_KEY);
        EventEnvelope envelope = new EventEnvelope(UuidV7.next(clock), type, schemaVersion, now,
                aggregateId, sequence, EventEnvelope.EventActor.of(actor), requestId, payload);
        jdbcTemplate.update("""
                INSERT INTO event_outbox (id, topic, event_type, aggregate_id, sequence, envelope, occurred_at,
                                          attempts, next_attempt_at, traceparent)
                VALUES (?, ?, ?, ?, ?, ?::json, ?, 0, ?, ?)
                """, envelope.eventId(), topic, type, aggregateId, sequence, JSON.writeValueAsString(envelope),
                Timestamp.from(now), Timestamp.from(now), traces.current());
        return envelope;
    }

    /**
     * Claims up to {@code limit} due events, at most one per aggregate and always its oldest unpublished one, leased
     * by the mechanism the payment commands use too ({@link OutboxClaims}). If this instance dies mid-send, the lease
     * runs out and another relay sends them again.
     */
    public List<PendingEvent> claim(int limit, Duration lease) {
        return claims.claim(TABLE, limit, lease,
                "id, topic, event_type, aggregate_id, sequence, envelope::text AS envelope, occurred_at, attempts, "
                        + "traceparent",
                (rs, row) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("event_type"), rs.getObject("aggregate_id", UUID.class), rs.getLong("sequence"),
                        rs.getString("envelope"), rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts"),
                        rs.getString("traceparent")));
    }

    /** Only after the broker acknowledged: a row is never marked for an event that might not be on the topic. */
    @Transactional
    public void markPublished(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        jdbcTemplate.update("UPDATE event_outbox SET published_at = ?, last_error = NULL WHERE id = ANY (?) "
                + "AND published_at IS NULL", Timestamp.from(now), eventIds.toArray(new UUID[0]));
    }

    /** A send failed: try again after the backoff. Later events of the aggregate wait behind it. */
    @Transactional
    public void retryAt(UUID eventId, Instant retryAt, String error) {
        jdbcTemplate.update("UPDATE event_outbox SET next_attempt_at = ?, last_error = ? WHERE id = ? "
                + "AND published_at IS NULL", Timestamp.from(retryAt), truncate(error), eventId);
    }

    /** Deletes up to {@code batch} events published before {@code before}; returns how many. */
    @Transactional
    public int prunePublishedBefore(Instant before, int batch) {
        return jdbcTemplate.update("""
                DELETE FROM event_outbox WHERE id IN (
                    SELECT id FROM event_outbox WHERE published_at < ? ORDER BY published_at LIMIT ?)
                """, Timestamp.from(before), batch);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
