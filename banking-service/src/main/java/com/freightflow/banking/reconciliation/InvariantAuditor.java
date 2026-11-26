package com.freightflow.banking.reconciliation;

import com.freightflow.banking.reconciliation.dto.InvariantReport;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The ledger's invariants, checked against what is stored, not what the code meant to store (ADR 0044). Every check
 * is a read in one repeatable-read transaction, so all of them see the same instant. Each publishes the number of rows
 * that break it ({@code freightflow.invariants.violations{invariant}}), and {@code InvariantViolated} pages on any.
 * Run on a schedule, by an operator or auditor through {@code GET /api/v1/ledger/invariants}, and by
 * {@code scripts/audit_invariants.py} after every chaos experiment and restore drill.
 *
 * <ul>
 *   <li>{@code journal-entries-balance}: an entry's postings sum to zero in each currency;</li>
 *   <li>{@code balances-reconcile}: every stored balance, shard and checkpoint matches its postings, and every
 *       currency's postings sum to zero (the reconciliation, ADR 0004 and 0010);</li>
 *   <li>{@code holds-captured-once}: a capture entry belongs to exactly one captured hold, never beyond its amount, and
 *       debits the payer what the hold says was captured;</li>
 *   <li>{@code customs-disbursements}: per currency, CUSTOMS_DISBURSEMENTS holds the duties captured minus the
 *       estimates settled (ADR 0036);</li>
 *   <li>{@code outbox-in-order}: the outbox's retained events of an aggregate are a gapless run ending at its last
 *       sequence, were published in sequence order, and none waits past the threshold (ADR 0025).</li>
 * </ul>
 */
@Component
public class InvariantAuditor {

    private static final Logger log = LoggerFactory.getLogger(InvariantAuditor.class);
    private static final int EXAMPLES = 5;

    /** Each invariant's query: one row per violation, its first column a short description of the row. */
    private static final Map<String, String> CHECKS = new LinkedHashMap<>();

    static {
        CHECKS.put("journal-entries-balance", """
                SELECT 'entry ' || journal_entry_id || ' ' || currency || ' sums to ' || SUM(amount)
                FROM postings GROUP BY journal_entry_id, currency HAVING SUM(amount) <> 0
                """);
        CHECKS.put("holds-captured-once", """
                SELECT 'capture entry ' || j.id || ' belongs to ' || COUNT(h.id) || ' holds'
                FROM journal_entries j LEFT JOIN holds h ON h.capture_journal_entry_id = j.id
                WHERE j.entry_type = 'CAPTURE' GROUP BY j.id HAVING COUNT(h.id) <> 1
                UNION ALL
                SELECT 'hold ' || id || ' captured beyond its amount'
                FROM holds WHERE captured_amount > amount OR captured_payer_amount > payer_amount
                UNION ALL
                SELECT 'hold ' || h.id || ' debited ' || COALESCE(-SUM(p.amount), 0) || ', captured '
                       || h.captured_payer_amount
                FROM holds h LEFT JOIN postings p
                    ON p.journal_entry_id = h.capture_journal_entry_id AND p.account_id = h.payer_account_id
                WHERE h.status = 'CAPTURED' GROUP BY h.id, h.captured_payer_amount
                HAVING COALESCE(-SUM(p.amount), 0) <> h.captured_payer_amount
                """);
        CHECKS.put("customs-disbursements", """
                SELECT a.currency || ' CUSTOMS_DISBURSEMENTS holds ' || COALESCE(posted.total, 0) || ', captured '
                       || COALESCE(captured.total, 0) || ' minus settled ' || COALESCE(settled.total, 0)
                FROM accounts a
                LEFT JOIN (SELECT account_id, SUM(amount) AS total FROM postings GROUP BY account_id) posted
                    ON posted.account_id = a.id
                LEFT JOIN (SELECT currency, SUM(captured_disbursement_amount) AS total FROM holds
                           WHERE status = 'CAPTURED' GROUP BY currency) captured ON captured.currency = a.currency
                LEFT JOIN (SELECT currency, SUM(estimate) AS total FROM customs_settlements GROUP BY currency) settled
                    ON settled.currency = a.currency
                WHERE a.account_type = 'CUSTOMS_DISBURSEMENTS'
                  AND COALESCE(posted.total, 0) <> COALESCE(captured.total, 0) - COALESCE(settled.total, 0)
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
    private final ReconciliationMonitor reconciliation;
    private final Clock clock;
    private final Duration stuckAfter;
    private final boolean enabled;
    private final Map<String, AtomicLong> violations = new ConcurrentHashMap<>();
    private final AtomicReference<Instant> lastRun;

    public InvariantAuditor(JdbcTemplate jdbc, ReconciliationMonitor reconciliation, MeterRegistry meters, Clock clock,
                            @Value("${freightflow.invariants.outbox-stuck-after:15m}") Duration stuckAfter,
                            @Value("${freightflow.invariants.enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.stuckAfter = stuckAfter;
        this.enabled = enabled;
        this.lastRun = new AtomicReference<>(clock.instant());
        List<String> names = new ArrayList<>(CHECKS.keySet());
        names.add(1, "balances-reconcile");
        for (String name : names) {
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

    /** Daily by default: the trial balance the brief asks for, and more. Experiments call it on demand. */
    @Scheduled(fixedDelayString = "${freightflow.invariants.interval:24h}",
            initialDelayString = "${freightflow.invariants.initial-delay:5m}")
    public void scheduledAudit() {
        if (enabled) {
            audit();
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public InvariantReport audit() {
        Instant now = clock.instant();
        List<InvariantReport.Invariant> results = new ArrayList<>();
        for (Map.Entry<String, String> check : CHECKS.entrySet()) {
            Object[] arguments = check.getValue().contains("?")
                    ? new Object[] {java.sql.Timestamp.from(now.minus(stuckAfter))} : new Object[0];
            List<String> rows = jdbc.queryForList(check.getValue(), String.class, arguments);
            results.add(InvariantReport.Invariant.of(check.getKey(), rows, EXAMPLES));
            if (check.getKey().equals("journal-entries-balance")) {
                results.add(reconciled());
            }
        }
        Map<String, InvariantReport.CaptureTotal> captures = new java.util.TreeMap<>();
        jdbc.query("""
                SELECT currency, COUNT(*) AS captures, SUM(captured_amount) AS amount
                FROM holds WHERE status = 'CAPTURED' GROUP BY currency
                """, row -> {
            captures.put(row.getString("currency"),
                    new InvariantReport.CaptureTotal(row.getLong("captures"), row.getBigDecimal("amount")));
        });
        InvariantReport report = new InvariantReport("banking-service", now, results, captures);
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

    private InvariantReport.Invariant reconciled() {
        var report = reconciliation.check();
        List<String> rows = new ArrayList<>();
        report.discrepancies().forEach(discrepancy -> rows.add("account " + discrepancy));
        report.trialBalances().stream().filter(balance -> !balance.balanced())
                .forEach(balance -> rows.add("trial balance " + balance));
        return InvariantReport.Invariant.of("balances-reconcile", rows, EXAMPLES);
    }
}
