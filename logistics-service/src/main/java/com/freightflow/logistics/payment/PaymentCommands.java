package com.freightflow.logistics.payment;

import com.freightflow.logistics.common.UuidV7;
import com.freightflow.logistics.outbox.OutboxClaims;
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

    /** Commands of one shipment need no order between them: each is enqueued only after the one it follows. */
    public static final OutboxClaims.Table TABLE =
            OutboxClaims.Table.unordered("shipment_payments", "{row}.status = 'PENDING'");

    private final JdbcTemplate jdbcTemplate;
    private final OutboxClaims claims;
    private final Clock clock;

    public PaymentCommands(JdbcTemplate jdbcTemplate, OutboxClaims claims, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.claims = claims;
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
        if (command == PaymentCommandType.EXTEND || command == PaymentCommandType.REAUTHORISE) {
            throw new IllegalArgumentException("Use enqueueExtension or enqueueReauthorisation for " + command);
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

    /** The key of the re-authorisation after one expired hold: one per expiry, stable across redeliveries. */
    public static String reauthorisationKey(UUID shipmentId, UUID expiredHoldId) {
        return "shipment-" + shipmentId + "-reauthorise-" + expiredHoldId;
    }

    /**
     * A REAUTHORISE after banking expired {@code expiredHoldId} (ADR 0028). Returns false if it exists already: the
     * key is per expired hold, and a partial unique index keeps one open per shipment.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean enqueueReauthorisation(UUID shipmentId, UUID expiredHoldId, BigDecimal amount, String currency,
                                          String payerAccountNumber, Instant expiresAt) {
        return insert(shipmentId, PaymentCommandType.REAUTHORISE, amount, currency, payerAccountNumber, expiredHoldId,
                expiresAt, reauthorisationKey(shipmentId, expiredHoldId)) == 1;
    }

    private int insert(UUID shipmentId, PaymentCommandType command, BigDecimal amount, String currency,
                       String payerAccountNumber, UUID holdId, Instant expiresAt, String key) {
        Instant now = clock.instant();
        // No conflict target: any of the unique indexes (one command per type, one open extension, the key) means
        // the command already exists.
        return jdbcTemplate.update("""
                INSERT INTO shipment_payments (id, shipment_id, command, status, amount, currency, payer_account_number,
                                               idempotency_key, hold_id, expires_at, attempts, next_attempt_at,
                                               created_at, updated_at, payer_organization_id, on_behalf_of_subject)
                SELECT ?, s.id, ?, 'PENDING', ?, ?, ?, ?, ?, ?, 0, ?, ?, ?, s.payer_organization_id, s.booked_by_subject
                FROM shipments s WHERE s.id = ?
                ON CONFLICT DO NOTHING
                """,
                UuidV7.next(clock), command.name(), amount, currency, payerAccountNumber, key, holdId,
                expiresAt == null ? null : Timestamp.from(expiresAt),
                Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), shipmentId);
    }

    /**
     * Claims up to {@code limit} due commands for this relay, leased (ADR 0025, {@link OutboxClaims}): the HTTP call
     * runs outside any transaction, and if this instance dies mid-call, the lease runs out and another relay sends
     * the command again, with the same key.
     */
    public List<UUID> claimDue(int limit, Duration lease) {
        return claims.claim(TABLE, limit, lease, "id", (rs, row) -> rs.getObject("id", UUID.class));
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
