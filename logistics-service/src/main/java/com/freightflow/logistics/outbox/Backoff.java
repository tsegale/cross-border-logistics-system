package com.freightflow.logistics.outbox;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Retry delay of an outbox row after a failed attempt (ADR 0025): exponential, capped, with jitter so rows that failed
 * together do not retry together.
 *
 * @param base first delay
 * @param max  cap
 */
public record Backoff(Duration base, Duration max) {

    /** base x 2^(attempt-1), capped, then a random point in [cap/2, cap]. */
    public Duration after(int attempt) {
        long cap = Math.min(max.toMillis(), base.toMillis() << Math.min(Math.max(attempt - 1, 0), 20));
        return Duration.ofMillis(cap / 2 + ThreadLocalRandom.current().nextLong(cap / 2 + 1));
    }
}
