package com.freightflow.logistics.payment;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * When a shipment's hold should expire (ADR 0011). The target is the end of the planned pickup day plus a margin; no
 * single request asks for more than {@code max-ttl} ahead, so a pickup months away is covered by extensions. Times
 * are whole seconds: they are part of an EXTEND command's idempotency key.
 */
@Component
public class HoldExpiryPolicy {

    private final HoldExpiryProperties properties;

    public HoldExpiryPolicy(HoldExpiryProperties properties) {
        this.properties = properties;
    }

    /** The expiry the AUTHORISE command asks for. */
    public Instant initialExpiry(LocalDate plannedPickup, Instant now) {
        return earlier(pickupExpiry(plannedPickup), now.plus(properties.maxTtl())).truncatedTo(ChronoUnit.SECONDS);
    }

    /**
     * The expiry to extend a hold to, or empty if it does not need extending yet. A shipment whose pickup date has
     * passed but which is still waiting is given {@code overdue-extension} more at a time; banking's max-lifetime
     * is the final limit.
     */
    public Optional<Instant> renewal(LocalDate plannedPickup, Instant currentExpiry, Instant now) {
        Instant renewalPoint = now.plus(properties.renewBefore());
        if (currentExpiry.isAfter(renewalPoint)) {
            return Optional.empty();
        }
        Instant wanted = pickupExpiry(plannedPickup);
        if (!wanted.isAfter(renewalPoint)) {
            wanted = now.plus(properties.overdueExtension());
        }
        Instant target = earlier(wanted, now.plus(properties.maxTtl())).truncatedTo(ChronoUnit.SECONDS);
        return target.isAfter(currentExpiry) ? Optional.of(target) : Optional.empty();
    }

    /** A booking's planned pickup must be today or later and within the horizon (UTC dates). */
    public boolean isAcceptablePickup(LocalDate plannedPickup, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate latest = LocalDate.ofInstant(now.plus(properties.maxPickupHorizon()), ZoneOffset.UTC);
        return !plannedPickup.isBefore(today) && !plannedPickup.isAfter(latest);
    }

    public HoldExpiryProperties properties() {
        return properties;
    }

    private Instant pickupExpiry(LocalDate plannedPickup) {
        return plannedPickup.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().plus(properties.margin());
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }
}
