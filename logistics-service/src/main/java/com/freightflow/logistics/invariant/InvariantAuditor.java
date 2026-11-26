package com.freightflow.logistics.invariant;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * logistics' invariants, checked against what is stored (ADR 0044), in one repeatable-read transaction:
 * <ul>
 *   <li>{@code shipments-paid-once}: a delivered shipment that is paid for has exactly one freight capture banking
 *       confirmed, or one still pending within the grace period; a duty hold is captured at most once, and once when
 *       the shipment says so (ADR 0038);</li>
 *   <li>{@code outbox-in-order}: the outbox's retained events of an aggregate are a gapless run ending at its last
 *       sequence, were published in sequence order, and none waits past the threshold (ADR 0025).</li>
 * </ul>
 * Each publishes {@code freightflow.invariants.violations{invariant}}; {@code InvariantViolated} pages on any. The
 * captures banking confirmed are reported per currency for the cross-service check in
 * {@code scripts/audit_invariants.py}.
 */
@Component
public class InvariantAuditor {

    private static final Logger log = LoggerFactory.getLogger(InvariantAuditor.class);
    private static final int EXAMPLES = 5;

    /** Each invariant's query: one row per violation, a short description of it. {@code ?} is the cut-off. */
    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("shipments-paid-once", """
                SELECT 'shipment ' || s.tracking_number || ' delivered with '
                       || COUNT(p.id) FILTER (WHERE p.status = 'SUCCEEDED') || ' freight captures'
                FROM shipments s
                LEFT JOIN shipment_payments p
                    ON p.shipment_id = s.id AND p.hold_kind = 'FREIGHT' AND p.command = 'CAPTURE'
                WHERE s.status = 'DELIVERED' AND s.payment_status <> 'NOT_REQUIRED'
                GROUP BY s.id, s.tracking_number
                HAVING COUNT(p.id) FILTER (WHERE p.status = 'SUCCEEDED') <> 1
                   -- No capture row at all leaves bool_or NULL, and NOT NULL would hide the shipment.
                   AND NOT (COUNT(p.id) FILTER (WHERE p.status = 'SUCCEEDED') = 0
                            AND COALESCE(bool_or(p.status = 'PENDING' AND p.created_at >= ?), false))
                UNION ALL
                SELECT 'shipment ' || s.tracking_number || ' has ' || COUNT(p.id) || ' duty captures, its duty hold '
                       || s.duty_hold_status
                FROM shipments s
                LEFT JOIN shipment_payments p
                    ON p.shipment_id = s.id AND p.hold_kind = 'DUTIES' AND p.command = 'CAPTURE'
                       AND p.status = 'SUCCEEDED'
                WHERE s.duty_hold_status IS NOT NULL
                GROUP BY s.id, s.tracking_number, s.duty_hold_status
                HAVING COUNT(p.id) > 1 OR ((s.duty_hold_status = 'CAPTURED') <> (COUNT(p.id) = 1))
                """);
        CHECKS.put("outbox-in-order", """
                SELECT 'aggregate ' || o.aggregate_id || ' keeps ' || COUNT(*) || ' events, sequences '
                       || MIN(o.sequence) || ' to ' || MAX(o.sequence) || ' of ' || s.last_sequence
                FROM event_outbox o JOIN event_sequences s ON s.aggregate_id = o.aggregate_id
                GROUP BY o.aggregate_id, s.last_sequence
                HAVING COUNT(*) <> MAX(o.sequence) - MIN(o.sequence) + 1 OR MAX(o.sequence) <> s.last_sequence
                UNION ALL
                SELECT 'aggregate ' || later.aggregate_id || ' published #' || later.sequence || ' before #'
                       || earlier.sequence
                FROM event_outbox earlier JOIN event_outbox later
                    ON later.aggregate_id = earlier.aggregate_id AND later.sequence > earlier.sequence
                WHERE later.published_at < earlier.published_at
                   OR (earlier.published_at IS NULL AND later.published_at IS NOT NULL)
                UNION ALL
                SELECT 'event ' || id || ' unpublished since ' || occurred_at
                FROM event_outbox WHERE published_at IS NULL AND occurred_at < ?
                """);
    }

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final Duration grace;
    private final boolean enabled;
    private final Map<String, AtomicLong> violations = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastRun;

    public InvariantAuditor(JdbcTemplate jdbc, MeterRegistry meters, Clock clock,
                            @Value("${freightflow.invariants.grace:15m}") Duration grace,
                            @Value("${freightflow.invariants.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.grace = grace;
        this.enabled = enabled;
        this.lastRun = new AtomicReference<>(clock.instant());
        for (String name : CHECKS.keySet()) {
            AtomicLong count = new AtomicLong();
            violations.put(name, count);
            Gauge.builder("freightflow.invariants.violations", count, AtomicLong::get)
                    .description("Rows that break the invariant at the last audit (ADR 0044)")
                    .tag("invariant", name)
                    .register(meters);
        }
        Gauge.builder("freightflow.invariants.audit.age", lastRun,
                        last -> Duration.between(last.get(), clock.instant()).toSeconds())
                .description("Seconds since the last completed invariant audit, or since startup before the first")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${freightflow.invariants.interval:24h}",
            initialDelayString = "${freightflow.invariants.initial-delay:5m}")
    public void scheduledAudit() {
        if (enabled) {
            audit();
        }
    }

    /** Every row that breaks one invariant, not only the report's first few. */
    @Transactional(readOnly = true)
    List<String> violations(String invariant) {
        return jdbc.queryForList(CHECKS.get(invariant), String.class, Timestamp.from(clock.instant().minus(grace)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvariantReport audit() {
        Instant now = clock.instant();
        Timestamp cutoff = Timestamp.from(now.minus(grace));
        List<InvariantReport.Invariant> results = new ArrayList<>();
        for (Map.Entry<String, String> check : CHECKS.entrySet()) {
            List<String> rows = jdbc.queryForList(check.getValue(), String.class, cutoff);
            results.add(InvariantReport.Invariant.of(check.getKey(), rows, EXAMPLES));
        }
        Map<String, InvariantReport.CaptureTotal> captures = new TreeMap<>();
        jdbc.query("""
                SELECT currency, COUNT(*) AS captures, SUM(amount) AS amount FROM shipment_payments
                WHERE command = 'CAPTURE' AND status = 'SUCCEEDED' GROUP BY currency
                """, row -> {
            captures.put(row.getString("currency"),
                    new InvariantReport.CaptureTotal(row.getLong("captures"), row.getBigDecimal("amount")));
        });
        InvariantReport report = new InvariantReport("logistics-service", now, results, captures);
        for (InvariantReport.Invariant invariant : results) {
            violations.get(invariant.name()).set(invariant.violations());
        }
        lastRun.set(clock.instant());
        if (!report.holds()) {
            log.error("Invariant audit: {} broken", report.broken());
        } else {
            log.info("Invariant audit: all {} invariants hold", results.size());
        }
        return report;
    }
}
