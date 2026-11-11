package com.freightflow.logistics.payment;

import com.freightflow.logistics.common.UuidV7;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Writes and claims payment commands (ADR 0012).
 *
 * <p>Enqueueing must join the caller's transaction, so the command commits or rolls back with the shipment change
 * that needs it: that is what makes this an outbox. Each shipment has at most one AUTHORISE, CAPTURE and RELEASE and
 * at most one open EXTEND, and every key is derived from the shipment id (and an extension's target expiry), so
 * enqueueing twice is a no-op and every retry of a command carries the same key.
 */
@Component
public class PaymentCommands {

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public PaymentCommands(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public static String idempotencyKey(UUID shipmentId, PaymentCommandType command) {
        return "shipment-" + shipmentId + "-" + command.name().toLowerCase(java.util.Locale.ROOT);
    }

    /** The key of one extension: the target expiry makes it unique per extension and stable across retries. */
    public static String extensionKey(UUID shipmentId, Instant expiresAt) {
        return "shipment-" + shipmentId + "-extend-" + expiresAt.getEpochSecond();
    }

    /** AUTHORISE, CAPTURE or RELEASE; {@code expiresAt} only for AUTHORISE (null leaves banking's default). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID shipmentId, PaymentCommandType command, BigDecimal amount, String currency,
                        String payerAccountNumber, UUID holdId, Instant expiresAt) {
        if (command == PaymentCommandType.EXTEND) {
            throw new IllegalArgumentException("Use enqueueExtension for EXTEND");
        }
        insert(shipmentId, command, amount, currency, payerAccountNumber, holdId, expiresAt,
                idempotencyKey(shipmentId, command));
    }

    /**
     * An EXTEND for the shipment's hold. Returns false if the shipment already has an open extension: the unique
     * index keeps it to one, also when several instances run the renewal job at once.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueueExtension(UUID shipmentId, UUID holdId, BigDecimal amount, String currency,
                                    String payerAccountNumber, Instant expiresAt) {
        return insert(shipmentId, PaymentCommandType.EXTEND, amount, currency, payerAccountNumber, holdId, expiresAt,
                extensionKey(shipmentId, expiresAt)) == 1;
    }

    private int insert(UUID shipmentId, PaymentCommandType command, BigDecimal amount, String currency,
                       String payerAccountNumber, UUID holdId, Instant expiresAt, String key) {
        Instant now = clock.instant();
        // No conflict target: any of the unique indexes (one command per type, one open extension, the key) means
        // the command already exists.
        return jdbcTemplate.update("""
                INSERT INTO shipment_payments (id, shipment_id, command, status, amount, currency, payer_account_number,
                                               idempotency_key, hold_id, expires_at, attempts, next_attempt_at,
                                               created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                UuidV7.next(clock), shipmentId, command.name(), amount, currency, payerAccountNumber, key, holdId,
                expiresAt == null ? null : Timestamp.from(expiresAt),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
    }

    /**
     * Claims up to {@code limit} due commands for this relay: {@code FOR UPDATE SKIP LOCKED} lets several instances
     * claim side by side without blocking or picking the same row, and pushing {@code next_attempt_at} out by the
     * lease keeps the row away from other relays while the HTTP call runs outside any transaction. If this
     * instance dies mid-call, the lease runs out and another relay sends the command again, with the same key.
     */
    @Transactional
    public List<UUID> claimDue(int limit, Duration lease) {
        Instant now = clock.instant();
        return jdbcTemplate.queryForList("""
                UPDATE shipment_payments
                SET next_attempt_at = ?, attempts = attempts + 1, updated_at = ?
                WHERE id IN (
                    SELECT id FROM shipment_payments
                    WHERE status = 'PENDING' AND next_attempt_at <= ?
                    ORDER BY next_attempt_at
                    LIMIT ?
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING id
                """, UUID.class,
                Timestamp.from(now.plus(lease)), Timestamp.from(now), Timestamp.from(now), limit);
    }

    /** Gives back an attempt that never reached banking (circuit open or bulkhead full): it was not a real try. */
    @Transactional
    public void refundAttempt(UUID commandId, Instant retryAt) {
        jdbcTemplate.update("""
                UPDATE shipment_payments SET attempts = GREATEST(attempts - 1, 0), next_attempt_at = ?, updated_at = ?
                WHERE id = ? AND status = 'PENDING'
                """, Timestamp.from(retryAt), Timestamp.from(clock.instant()), commandId);
    }

    public int countParked() {
        Integer parked = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM shipment_payments WHERE status = 'PARKED'", Integer.class);
        return parked == null ? 0 : parked;
    }
}
