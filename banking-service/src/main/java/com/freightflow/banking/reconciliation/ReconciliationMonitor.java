package com.freightflow.banking.reconciliation;

import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the ledger reconciliation (ADR 0004) on a schedule and publishes what it found, so drift is an alert and not a
 * report somebody has to ask for (docs/slo.md: drift is never acceptable, a hard alert rather than an objective).
 *
 * <ul>
 *   <li>{@code freightflow.ledger.discrepancies}: accounts whose stored balance differs from their postings;</li>
 *   <li>{@code freightflow.ledger.unbalanced.currencies}: currencies whose postings do not sum to zero;</li>
 *   <li>{@code freightflow.ledger.reconciliation.age}: seconds since the last completed run (since startup before
 *       the first), so a check that never ran or stopped running is noticed too.</li>
 * </ul>
 */
@Component
public class ReconciliationMonitor {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationMonitor.class);

    private final ReconciliationService reconciliation;
    private final Clock clock;
    private final boolean enabled;
    private final AtomicLong discrepancies = new AtomicLong();
    private final AtomicLong unbalancedCurrencies = new AtomicLong();
    private final AtomicReference<Instant> lastRun = new AtomicReference<>();

    public ReconciliationMonitor(ReconciliationService reconciliation, MeterRegistry meters, Clock clock,
                                 @Value("${freightflow.banking.reconciliation.enabled:true}") boolean enabled) {
        this.reconciliation = reconciliation;
        this.clock = clock;
        this.enabled = enabled;
        this.lastRun.set(clock.instant());
        Gauge.builder("freightflow.ledger.discrepancies", discrepancies, AtomicLong::get)
                .description("Accounts whose stored balance differs from the sum of their postings")
                .register(meters);
        Gauge.builder("freightflow.ledger.unbalanced.currencies", unbalancedCurrencies, AtomicLong::get)
                .description("Currencies whose postings do not sum to zero")
                .register(meters);
        Gauge.builder("freightflow.ledger.reconciliation.age", lastRun, this::ageSeconds)
                .description("Seconds since the last completed reconciliation, or since startup before the first")
                .baseUnit("seconds")
                .register(meters);
    }

    @Scheduled(fixedDelayString = "${freightflow.banking.reconciliation.interval:5m}",
            initialDelayString = "${freightflow.banking.reconciliation.initial-delay:1m}")
    public void scheduledCheck() {
        if (enabled) {
            check();
        }
    }

    /** One reconciliation; returns the report it published. */
    public ReconciliationReportResponse check() {
        ReconciliationReportResponse report = reconciliation.reconcile();
        discrepancies.set(report.discrepancies().size());
        unbalancedCurrencies.set(report.trialBalances().stream().filter(balance -> !balance.balanced()).count());
        lastRun.set(clock.instant());
        if (!report.balanced()) {
            log.error("Ledger reconciliation found {} account discrepancies and {} unbalanced currencies",
                    discrepancies.get(), unbalancedCurrencies.get());
        }
        return report;
    }

    private double ageSeconds(AtomicReference<Instant> last) {
        return Duration.between(last.get(), clock.instant()).toMillis() / 1000.0;
    }
}
