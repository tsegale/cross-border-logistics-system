package com.freightflow.banking.hold;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * @param defaultTtl    how long a hold lasts when the request gives no expiresAt
 * @param maxTtl        the latest expiresAt a request or an extension may ask for, from now
 * @param maxLifetime   the latest a hold may ever expire, from its creation, however often it is extended
 * @param sweepInterval how often the sweeper looks for expired holds
 * @param sweepBatch    how many expired holds one sweep releases at most
 */
@ConfigurationProperties(prefix = "freightflow.banking.holds")
public record HoldProperties(Duration defaultTtl, Duration maxTtl, Duration maxLifetime, Duration sweepInterval,
                             int sweepBatch) {

    public HoldProperties {
        defaultTtl = defaultTtl == null ? Duration.ofDays(7) : defaultTtl;
        maxTtl = maxTtl == null ? Duration.ofDays(30) : maxTtl;
        maxLifetime = maxLifetime == null ? Duration.ofDays(120) : maxLifetime;
        sweepInterval = sweepInterval == null ? Duration.ofMinutes(1) : sweepInterval;
        sweepBatch = sweepBatch <= 0 ? 100 : sweepBatch;
        if (defaultTtl.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("holds.default-ttl must not exceed holds.max-ttl");
        }
        if (maxTtl.compareTo(maxLifetime) > 0) {
            throw new IllegalArgumentException("holds.max-ttl must not exceed holds.max-lifetime");
        }
    }
}
