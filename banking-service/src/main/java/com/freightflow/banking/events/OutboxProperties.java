package com.freightflow.banking.events;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Event outbox and relay settings (ADR 0025).
 *
 * @param relayEnabled    whether the scheduled relay runs; tests drive {@link EventRelay#relayOnce()} themselves
 * @param relayInterval   pause between relay runs
 * @param batchSize       events claimed per round
 * @param maxRounds       rounds per run: one round sends at most one event per aggregate, so a burst on one
 *                        aggregate drains in consecutive rounds instead of waiting for the next run
 * @param lease           how long a claimed event stays away from other relays; longer than the producer's
 *                        delivery timeout, so a send has finished or failed before another relay may try it
 * @param sendTimeout     how long a round waits for the broker's acknowledgements
 * @param retryBackoff    first retry delay after a failed send; doubles per attempt, with jitter
 * @param maxRetryBackoff cap on the retry delay
 * @param retention       how long published events stay in the table (for inspection and replay by hand)
 * @param pruneBatch      rows deleted per statement when pruning
 */
@ConfigurationProperties(prefix = "freightflow.events.outbox")
public record OutboxProperties(boolean relayEnabled, Duration relayInterval, int batchSize, int maxRounds,
                               Duration lease, Duration sendTimeout, Duration retryBackoff, Duration maxRetryBackoff,
                               Duration retention, int pruneBatch) {

    public OutboxProperties {
        relayInterval = relayInterval == null ? Duration.ofMillis(200) : relayInterval;
        batchSize = batchSize <= 0 ? 200 : batchSize;
        maxRounds = maxRounds <= 0 ? 20 : maxRounds;
        lease = lease == null ? Duration.ofSeconds(60) : lease;
        sendTimeout = sendTimeout == null ? Duration.ofSeconds(30) : sendTimeout;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(1) : retryBackoff;
        maxRetryBackoff = maxRetryBackoff == null ? Duration.ofMinutes(1) : maxRetryBackoff;
        retention = retention == null ? Duration.ofDays(7) : retention;
        pruneBatch = pruneBatch <= 0 ? 1000 : pruneBatch;
        if (lease.compareTo(sendTimeout) <= 0) {
            throw new IllegalArgumentException("freightflow.events.outbox.lease (" + lease
                    + ") must be longer than send-timeout (" + sendTimeout + ")");
        }
    }
}
