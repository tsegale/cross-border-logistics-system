package com.freightflow.banking.approval;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Four-eyes approvals (ADR 0021).
 *
 * @param ttl           how long a request waits for a second operator before it expires
 * @param sweepInterval how often the sweeper marks requests past their expiry as EXPIRED
 * @param sweepBatch    how many requests one sweep expires at most
 */
@ConfigurationProperties(prefix = "freightflow.banking.approvals")
public record ApprovalProperties(Duration ttl, Duration sweepInterval, int sweepBatch) {

    public ApprovalProperties {
        ttl = ttl == null ? Duration.ofHours(24) : ttl;
        sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
        sweepBatch = sweepBatch <= 0 ? 100 : sweepBatch;
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("approvals.ttl must be positive, was " + ttl);
        }
    }
}
