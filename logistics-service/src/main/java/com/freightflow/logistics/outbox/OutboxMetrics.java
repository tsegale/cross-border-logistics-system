package com.freightflow.logistics.outbox;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The same two gauges for every outbox (ADR 0025), told apart by the {@code outbox} tag: how many rows wait, and how
 * long the oldest has waited. Read from the table on each scrape, so a relay that stopped cannot report a stale value.
 */
public final class OutboxMetrics {

    private OutboxMetrics() {
    }

    public static void register(MeterRegistry meters, OutboxClaims claims, String outbox, OutboxClaims.Table table,
                                String timeColumn) {
        Gauge.builder("freightflow.outbox.backlog", claims, c -> c.backlog(table))
                .description("Rows written but not yet relayed")
                .tag("outbox", outbox)
                .register(meters);
        Gauge.builder("freightflow.outbox.oldest.age", claims, c -> c.oldestPendingAgeSeconds(table, timeColumn))
                .description("Seconds since the oldest row still to be relayed was written")
                .tag("outbox", outbox)
                .baseUnit("seconds")
                .register(meters);
    }
}
