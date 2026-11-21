package com.freightflow.notification.telemetry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.DoubleSupplier;

/**
 * A gauge read from a table, which a scrape waits on for at most 250 ms (ADR 0031). The alert fire drill showed why:
 * with its database down, a service whose gauges queried it on every scrape took longer than the scrape timeout to
 * answer, so the whole scrape failed and the request metrics, the very SLIs of the outage, went missing with it. Now
 * the query runs on two threads of its own; a scrape gets the fresh value if the query answers in time and the last one
 * if it does not, and at most one query per gauge is in flight. Register it with {@code strongReference(true)}.
 */
public final class BoundedGauge {

    private static final long WAIT_MS = 250;
    private static final ExecutorService QUERIES = Executors.newFixedThreadPool(2,
            Thread.ofPlatform().name("table-gauge-", 0).daemon(true).factory());

    private final DoubleSupplier query;
    private volatile double last = Double.NaN;
    private CompletableFuture<Double> inFlight;

    public BoundedGauge(DoubleSupplier query) {
        this.query = query;
    }

    public synchronized double value() {
        if (inFlight == null || inFlight.isDone()) {
            inFlight = CompletableFuture.supplyAsync(query::getAsDouble, QUERIES)
                    .whenComplete((value, error) -> {
                        if (error == null) {
                            last = value;
                        }
                    });
        }
        try {
            return inFlight.get(WAIT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException | ExecutionException slowOrFailed) {
            return last;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return last;
        }
    }
}
