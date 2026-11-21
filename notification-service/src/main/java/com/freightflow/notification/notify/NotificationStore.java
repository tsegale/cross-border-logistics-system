package com.freightflow.notification.notify;

import com.freightflow.notification.common.UuidV7;
import com.freightflow.notification.security.Actor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code notifications} table (ADR 0027): one row per email, written by the event consumers in the transaction
 * that consumed the event, then sent by {@link EmailSender}.
 *
 * <p>Sending is at most once. A row is claimed by setting it SENDING with a lease, the email goes to SMTP, and the
 * row becomes SENT. An SMTP server that refuses puts it back to PENDING with backoff (nothing was sent). A sender
 * that dies after handing the email over leaves the row SENDING; once the lease is gone it becomes UNKNOWN and is
 * never sent again: a customer may miss one email, but never gets two.
 */
@Component
public class NotificationStore {

    public enum Status { PENDING, SENDING, SENT, FAILED, UNKNOWN }

    public record Notification(UUID id, UUID eventId, String eventType, NotificationKind kind, UUID organizationId,
                               UUID shipmentId, String recipient, String language, String subject, String bodyText,
                               String bodyHtml, Status status, int attempts, String lastError, Instant createdAt,
                               Instant sentAt, int resendCount, Instant resentAt, String resentBySubject) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public NotificationStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /** One email for one event and recipient; nothing if that pair exists already. Returns whether it was new. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean add(UUID eventId, String eventType, NotificationKind kind, UUID organizationId, UUID shipmentId,
                       String recipient, String language, EmailRenderer.Email email) {
        Instant now = clock.instant();
        return jdbcTemplate.update("""
                INSERT INTO notifications (id, event_id, event_type, kind, organization_id, shipment_id, recipient,
                                           language, subject, body_text, body_html, status, attempts, next_attempt_at,
                                           created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?)
                ON CONFLICT (event_id, recipient) DO NOTHING
                """, UuidV7.next(clock), eventId, eventType, kind.code(), organizationId, shipmentId, recipient,
                language, email.subject(), email.text(), email.html(), Timestamp.from(now), Timestamp.from(now)) == 1;
    }

    /** Claims up to {@code limit} due emails: SENDING, leased, one attempt more ({@code FOR UPDATE SKIP LOCKED}). */
    @Transactional
    public List<Notification> claim(int limit, Duration lease) {
        Instant now = clock.instant();
        return jdbcTemplate.query("""
                UPDATE notifications SET status = 'SENDING', attempts = attempts + 1, next_attempt_at = ?
                WHERE id IN (
                    SELECT id FROM notifications WHERE status = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING *
                """, NotificationStore::map, Timestamp.from(now.plus(lease)), Timestamp.from(now), limit);
    }

    @Transactional
    public void sent(UUID id) {
        jdbcTemplate.update("UPDATE notifications SET status = 'SENT', sent_at = ?, last_error = NULL "
                + "WHERE id = ? AND status = 'SENDING'", Timestamp.from(clock.instant()), id);
    }

    /** The server refused: nothing was delivered. Try again at {@code retryAt}, or give up. */
    @Transactional
    public void refused(UUID id, String error, Instant retryAt, boolean giveUp) {
        jdbcTemplate.update("UPDATE notifications SET status = ?, next_attempt_at = ?, last_error = ? "
                        + "WHERE id = ? AND status = 'SENDING'", giveUp ? "FAILED" : "PENDING",
                Timestamp.from(retryAt), truncate(error), id);
    }

    /** Emails whose sender died mid-send: UNKNOWN, never sent again. Returns how many. */
    @Transactional
    public int abandonExpiredLeases() {
        return jdbcTemplate.update("""
                UPDATE notifications SET status = 'UNKNOWN',
                    last_error = 'The sender stopped while this email was being sent; it is not sent again'
                WHERE status = 'SENDING' AND next_attempt_at < ?
                """, Timestamp.from(clock.instant()));
    }

    public Optional<Notification> find(UUID id) {
        return jdbcTemplate.query("SELECT * FROM notifications WHERE id = ?", NotificationStore::map, id).stream()
                .findFirst();
    }

    /** The row, locked while a resend is decided. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Notification> lock(UUID id) {
        return jdbcTemplate.query("SELECT * FROM notifications WHERE id = ? FOR UPDATE", NotificationStore::map, id)
                .stream().findFirst();
    }

    /**
     * An operator sends an UNKNOWN email again: PENDING now with a fresh set of attempts, the operator recorded
     * (ADR 0020). Only from UNKNOWN; returns whether the row changed.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean resend(UUID id, Actor actor) {
        Instant now = clock.instant();
        return jdbcTemplate.update("""
                UPDATE notifications SET status = 'PENDING', attempts = 0, next_attempt_at = ?,
                    resend_count = resend_count + 1, resent_at = ?, resent_by_subject = ?, resent_by_client_id = ?,
                    resend_request_id = ?
                WHERE id = ? AND status = 'UNKNOWN'
                """, Timestamp.from(now), Timestamp.from(now), actor.subject(), actor.clientId(), actor.requestId(),
                id) == 1;
    }

    public List<Notification> forEvent(UUID eventId) {
        return jdbcTemplate.query("SELECT * FROM notifications WHERE event_id = ? ORDER BY recipient",
                NotificationStore::map, eventId);
    }

    /** Newest first, of one organisation or (null) of all, in one status or (null) any. */
    public List<Notification> history(UUID organizationId, Status status, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM notifications WHERE true");
        List<Object> args = new ArrayList<>();
        if (organizationId != null) {
            sql.append(" AND organization_id = ?");
            args.add(organizationId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), NotificationStore::map, args.toArray());
    }

    public long count(Status status) {
        Long count = jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE status = ?", Long.class,
                status.name());
        return count == null ? 0 : count;
    }

    /** Deletes notifications, recipient included, older than the retention. */
    @Transactional
    public int pruneCreatedBefore(Instant before) {
        return jdbcTemplate.update("DELETE FROM notifications WHERE created_at < ? AND status <> 'SENDING'",
                Timestamp.from(before));
    }

    private static Notification map(ResultSet rs, int row) throws SQLException {
        Timestamp sentAt = rs.getTimestamp("sent_at");
        Timestamp resentAt = rs.getTimestamp("resent_at");
        return new Notification(rs.getObject("id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getString("event_type"), NotificationKind.of(rs.getString("kind")).orElseThrow(),
                rs.getObject("organization_id", UUID.class), rs.getObject("shipment_id", UUID.class),
                rs.getString("recipient"), rs.getString("language"), rs.getString("subject"),
                rs.getString("body_text"), rs.getString("body_html"), Status.valueOf(rs.getString("status")),
                rs.getInt("attempts"), rs.getString("last_error"), rs.getTimestamp("created_at").toInstant(),
                sentAt == null ? null : sentAt.toInstant(), rs.getInt("resend_count"),
                resentAt == null ? null : resentAt.toInstant(), rs.getString("resent_by_subject"));
    }

    private static String truncate(String value) {
        return value == null || value.length() <= 500 ? value : value.substring(0, 500);
    }
}
