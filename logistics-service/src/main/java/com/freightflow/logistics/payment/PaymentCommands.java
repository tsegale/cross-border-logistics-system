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
 * that needs it: that is what makes this an outbox. Each shipment has at most one command of each type and its
 * idempotency key is derived from the shipment id, so enqueueing twice is a no-op and every retry of a command
 * carries the same key.
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

    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(UUID shipmentId, PaymentCommandType command, BigDecimal amount, String currency,
                        String payerAccountNumber, UUID holdId) {
        Instant now = clock.instant();
        jdbcTemplate.update("""
                INSERT INTO shipment_payments (id, shipment_id, command, status, amount, currency, payer_account_number,
                                               idempotency_key, hold_id, attempts, next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, 0, ?, ?, ?)
                ON CONFLICT (shipment_id, command) DO NOTHING
                """,
                UuidV7.next(clock), shipmentId, command.name(), amount, currency, payerAccountNumber,
                idempotencyKey(shipmentId, command), holdId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
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
