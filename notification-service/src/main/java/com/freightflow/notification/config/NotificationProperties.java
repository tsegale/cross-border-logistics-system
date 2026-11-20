package com.freightflow.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Email delivery settings (ADR 0027).
 *
 * @param from            the sender address
 * @param sendInterval    pause between sender runs
 * @param sendBatch       emails claimed per run
 * @param lease           how long a claimed email may stay SENDING; past it, a sender is presumed dead and the email
 *                        becomes UNKNOWN, never sent again
 * @param maxAttempts     sends refused by the SMTP server before the email is FAILED
 * @param retryBackoff    first retry delay; doubles per attempt, with jitter
 * @param maxRetryBackoff cap on the retry delay
 * @param retention       how long notifications (with their recipient) are kept
 */
@ConfigurationProperties(prefix = "freightflow.notifications")
public record NotificationProperties(String from, Duration sendInterval, int sendBatch, Duration lease,
                                     int maxAttempts, Duration retryBackoff, Duration maxRetryBackoff,
                                     Duration retention) {

    public NotificationProperties {
        from = from == null || from.isBlank() ? "FreightFlow <notifications@freightflow.dev>" : from;
        sendInterval = sendInterval == null ? Duration.ofMillis(500) : sendInterval;
        sendBatch = sendBatch <= 0 ? 50 : sendBatch;
        lease = lease == null ? Duration.ofMinutes(2) : lease;
        maxAttempts = maxAttempts <= 0 ? 6 : maxAttempts;
        retryBackoff = retryBackoff == null ? Duration.ofSeconds(2) : retryBackoff;
        maxRetryBackoff = maxRetryBackoff == null ? Duration.ofMinutes(5) : maxRetryBackoff;
        retention = retention == null ? Duration.ofDays(90) : retention;
    }
}
