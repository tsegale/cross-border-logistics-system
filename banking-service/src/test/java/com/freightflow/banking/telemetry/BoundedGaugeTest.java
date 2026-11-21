package com.freightflow.banking.telemetry;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** A table gauge never holds a scrape up for longer than its bound, and keeps its last value meanwhile (ADR 0031). */
class BoundedGaugeTest {

    @Test
    void aFastQueryIsReadFreshEveryTime() {
        AtomicInteger rows = new AtomicInteger(3);
        BoundedGauge gauge = new BoundedGauge(rows::get);

        assertThat(gauge.value()).isEqualTo(3.0);
        rows.set(4);
        assertThat(gauge.value()).isEqualTo(4.0);
    }

    @Test
    void aHangingQueryGivesTheLastValueWithinTheBound() throws Exception {
        CountDownLatch database = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        BoundedGauge gauge = new BoundedGauge(() -> {
            if (calls.incrementAndGet() > 1) {
                try {
                    database.await(10, TimeUnit.SECONDS); // a paused database
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return 7;
        });
        assertThat(gauge.value()).isEqualTo(7.0);

        long started = System.nanoTime();
        assertThat(gauge.value()).as("the last value, while the query hangs").isEqualTo(7.0);
        assertThat(gauge.value()).as("no second query while one is in flight").isEqualTo(7.0);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isLessThan(1500);
        assertThat(calls.get()).isEqualTo(2);
        database.countDown();
    }

    @Test
    void aFailingQueryGivesTheLastValue() {
        AtomicInteger calls = new AtomicInteger();
        BoundedGauge gauge = new BoundedGauge(() -> {
            if (calls.incrementAndGet() > 1) {
                throw new IllegalStateException("database down");
            }
            return 5;
        });

        assertThat(gauge.value()).isEqualTo(5.0);
        assertThat(gauge.value()).isEqualTo(5.0);
    }
}
