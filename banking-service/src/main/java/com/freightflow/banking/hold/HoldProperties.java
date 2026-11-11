package com.freightflow.banking.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param defaultTtl    how long a hold lasts when the request gives no expiresAt
 * @param maxTtl        the latest expiresAt a request may ask for
 * @param sweepInterval how often the sweeper looks for expired holds
 * @param sweepBatch    how many expired holds one sweep releases at most
 */
@ConfigurationProperties(prefix = "freightflow.banking.holds")
public record HoldProperties(Duration defaultTtl, Duration maxTtl, Duration sweepInterval, int sweepBatch) {

    public HoldProperties {
        defaultTtl = defaultTtl == null ? Duration.ofDays(7) : defaultTtl;
        maxTtl = maxTtl == null ? Duration.ofDays(30) : maxTtl;
        sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
        sweepBatch = sweepBatch <= 0 ? 100 : sweepBatch;
        if (defaultTtl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("holds.default-ttl must not exceed holds.max-ttl");
        }
    }
}
