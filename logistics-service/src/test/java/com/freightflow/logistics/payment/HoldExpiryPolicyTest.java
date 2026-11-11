package com.freightflow.logistics.payment;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HoldExpiryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-11T09:30:15.123Z");
    private final HoldExpiryPolicy policy = new HoldExpiryPolicy(
            new HoldExpiryProperties(null, null, null, null, null, true, null, 0));

    @Test
    void aNearPickupIsCoveredByTheFirstHoldWithTheMargin() {
        // End of 15 November plus 3 days.
        assertThat(policy.initialExpiry(LocalDate.parse("2026-11-15"), NOW)).isEqualTo("2026-11-19T00:00:00Z");
    }

    @Test
    void aDistantPickupIsCappedAtMaxTtlInWholeSeconds() {
        assertThat(policy.initialExpiry(LocalDate.parse("2027-01-20"), NOW)).isEqualTo("2026-12-10T09:30:15Z");
    }

    @Test
    void nothingIsRenewedWhileTheHoldHasMoreThanRenewBeforeLeft() {
        assertThat(policy.renewal(LocalDate.parse("2027-01-20"), NOW.plus(Duration.ofDays(4)), NOW)).isEmpty();
    }

    @Test
    void aHoldAboutToRunOutIsExtendedTowardsThePickup() {
        Instant current = NOW.plus(Duration.ofDays(2));
        assertThat(policy.renewal(LocalDate.parse("2027-01-20"), current, NOW)).contains(Instant.parse("2026-12-10T09:30:15Z"));
        assertThat(policy.renewal(LocalDate.parse("2026-11-20"), current, NOW)).contains(Instant.parse("2026-11-24T00:00:00Z"));
    }

    @Test
    void anOverdueShipmentGetsAnotherWeekAtATime() {
        Instant current = NOW.plus(Duration.ofHours(20));
        assertThat(policy.renewal(LocalDate.parse("2026-11-05"), current, NOW))
                .contains(Instant.parse("2026-11-18T09:30:15Z"));
    }

    @Test
    void pickupMustBeTodayOrLaterAndWithinTheHorizon() {
        assertThat(policy.isAcceptablePickup(LocalDate.parse("2026-11-11"), NOW)).isTrue();
        assertThat(policy.isAcceptablePickup(LocalDate.parse("2026-11-10"), NOW)).isFalse();
        assertThat(policy.isAcceptablePickup(LocalDate.parse("2027-02-09"), NOW)).isTrue();
        assertThat(policy.isAcceptablePickup(LocalDate.parse("2027-02-10"), NOW)).isFalse();
    }

    @Test
    void renewBeforeMustLeaveRoomBelowMaxTtl() {
        assertThatThrownBy(() -> new HoldExpiryProperties(null, Duration.ofDays(3), Duration.ofDays(3), Duration.ofDays(1),
                null, true, null, 0)).isInstanceOf(IllegalArgumentException.class);
    }
}
