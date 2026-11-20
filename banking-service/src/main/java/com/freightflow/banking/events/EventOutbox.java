package com.freightflow.banking.events;

import com.freightflow.banking.common.RequestIdFilter;
import com.freightflow.banking.common.UuidV7;
import com.freightflow.banking.security.Actor;
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
 * unpublished, no relay, on this instance or another, can claim the next one. Claimed rows are leased with
 * {@code FOR UPDATE SKIP LOCKED}, so relays on several instances share the work without blocking each other.
 */
@Component
public class EventOutbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public EventOutbox(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** One claimed event, ready to send. */
    public record PendingEvent(UUID eventId, String topic, String type, UUID aggregateId, long sequence,
                               String envelope, Instant occurredAt, int attempts) {
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
                                          attempts, next_attempt_at)
                VALUES (?, ?, ?, ?, ?, ?::json, ?, 0, ?)
                """, envelope.eventId(), topic, type, aggregateId, sequence, JSON.writeValueAsString(envelope),
                Timestamp.from(now), Timestamp.from(now));
        return envelope;
    }

    /**
     * Claims up to {@code limit} due events, at most one per aggregate and always its oldest unpublished one, and
     * leases them: {@code next_attempt_at} moves out by the lease, so no other relay claims them while this one
     * sends. If this instance dies mid-send, the lease runs out and another relay sends them again.
     */
    @Transactional
    public List<PendingEvent> claim(int limit, Duration lease) {
        Instant now = clock.instant();
        return jdbcTemplate.query("""
                UPDATE event_outbox SET next_attempt_at = ?, attempts = attempts + 1
                WHERE id IN (
                    SELECT o.id FROM event_outbox o
                    WHERE o.published_at IS NULL AND o.next_attempt_at <= ?
                      AND NOT EXISTS (
                          SELECT 1 FROM event_outbox earlier
                          WHERE earlier.aggregate_id = o.aggregate_id AND earlier.published_at IS NULL
                            AND earlier.sequence < o.sequence)
                    ORDER BY o.next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id, topic, event_type, aggregate_id, sequence, envelope::text AS envelope, occurred_at, attempts
                """,
                (rs, row) -> new PendingEvent(rs.getObject("id", UUID.class), rs.getString("topic"),
                        rs.getString("event_type"), rs.getObject("aggregate_id", UUID.class), rs.getLong("sequence"),
                        rs.getString("envelope"), rs.getTimestamp("occurred_at").toInstant(), rs.getInt("attempts")),
                Timestamp.from(now.plus(lease)), Timestamp.from(now), limit);
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

    public long backlog() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    /** Seconds since the oldest unpublished event occurred; 0 when nothing waits. */
    public double oldestUnpublishedAgeSeconds() {
        Timestamp oldest = jdbcTemplate.queryForObject(
                "SELECT min(occurred_at) FROM event_outbox WHERE published_at IS NULL", Timestamp.class);
        return oldest == null ? 0 : Math.max(0, Duration.between(oldest.toInstant(), clock.instant()).toMillis() / 1000.0);
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
