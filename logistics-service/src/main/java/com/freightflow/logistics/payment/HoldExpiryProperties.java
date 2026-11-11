package com.freightflow.logistics.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * How long holds are asked to last (ADR 0011, ADR 0012).
 *
 * @param margin             how long after the end of the planned pickup day the hold should still be valid
 * @param maxTtl             the furthest ahead an expiry is asked for; below banking's {@code holds.max-ttl} (30 days)
 *                           so clock skew between the services never makes banking refuse it
 * @param renewBefore        a hold that expires sooner than this is extended
 * @param overdueExtension   how far an extension reaches when the planned pickup has already passed
 * @param maxPickupHorizon   the latest planned pickup a booking may give; with the margin it stays inside banking's
 *                           {@code holds.max-lifetime} (120 days)
 * @param renewalEnabled     off in tests that drive the job by hand
 * @param renewalInterval    pause between runs of the renewal job
 * @param renewalBatch       shipments looked at per run
 */
@ConfigurationProperties(prefix = "freightflow.logistics.payments.hold-expiry")
public record HoldExpiryProperties(Duration margin, Duration maxTtl, Duration renewBefore, Duration overdueExtension,
                                   Duration maxPickupHorizon, boolean renewalEnabled, Duration renewalInterval,
                                   int renewalBatch) {

    public HoldExpiryProperties {
        margin = margin == null ? Duration.ofDays(3) : margin;
        maxTtl = maxTtl == null ? Duration.ofDays(29) : maxTtl;
        renewBefore = renewBefore == null ? Duration.ofDays(3) : renewBefore;
        overdueExtension = overdueExtension == null ? Duration.ofDays(7) : overdueExtension;
        maxPickupHorizon = maxPickupHorizon == null ? Duration.ofDays(90) : maxPickupHorizon;
        renewalInterval = renewalInterval == null ? Duration.ofHours(1) : renewalInterval;
        renewalBatch = renewalBatch <= 0 ? 100 : renewalBatch;
        if (renewBefore.compareTo(maxTtl) >= 0 || overdueExtension.compareTo(maxTtl) > 0) {
            throw new IllegalArgumentException("hold-expiry.renew-before and overdue-extension must be below max-ttl");
        }
    }
}
