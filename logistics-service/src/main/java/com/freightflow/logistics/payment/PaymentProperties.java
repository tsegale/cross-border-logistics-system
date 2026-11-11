package com.freightflow.logistics.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Payment relay settings (ADR 0012).
 *
 * @param relayInterval   pause between relay runs
 * @param batchSize       commands claimed per run
 * @param lease           how long a claimed command stays away from other relays; longer than one call's budget
 * @param maxAttempts     attempts with an unknown outcome before the command is parked for an operator
 * @param retryBackoff    first retry delay; doubles per attempt, with jitter
 * @param maxRetryBackoff cap on the retry delay
 * @param parallelism     commands of one batch sent at once; matches the payments bulkhead (ADR 0013)
 */
@ConfigurationProperties(prefix = "freightflow.logistics.payments")
public record PaymentProperties(Duration relayInterval, int batchSize, Duration lease, int maxAttempts,
                                Duration retryBackoff, Duration maxRetryBackoff, int parallelism) {

    public PaymentProperties {
        relayInterval = relayInterval == null ? Duration.ofMillis(500) : relayInterval;
        batchSize = batchSize <= 0 ? 20 : batchSize;
        lease = lease == null ? Duration.ofSeconds(30) : lease;
        maxAttempts = maxAttempts <= 0 ? 8 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(1) : retryBackoff;
        maxRetryBackoff = maxRetryBackoff == null ? Duration.ofMinutes(5) : maxRetryBackoff;
        parallelism = parallelism <= 0 ? 8 : parallelism;
    }
}
