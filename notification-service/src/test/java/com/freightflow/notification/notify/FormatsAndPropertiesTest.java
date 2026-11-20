package com.freightflow.notification.notify;

import com.freightflow.notification.config.NotificationProperties;
import com.freightflow.notification.events.ConsumedEvent;
import com.freightflow.notification.settings.NotificationSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Locale formats, defaults and the small decisions around them (ADR 0027). */
class FormatsAndPropertiesTest {

    @Test
    void moneyIsRoundedHalfEvenToTheCurrencysMinorUnitsAndWrittenTheReadersWay() {
        assertThat(Formats.money(new BigDecimal("1840.5050"), "EUR", Locale.GERMANY)).isEqualTo("1.840,50 €");
        assertThat(Formats.money(new BigDecimal("1840.5150"), "EUR", Locale.UK)).isEqualTo("€1,840.52");
        assertThat(Formats.money(new BigDecimal("20000"), "NAD", Locale.GERMANY)).isEqualTo("20.000,00 NAD");
    }

    @Test
    void anInstantIsTheCalendarDateInTheOrganisationsTimeZone() {
        Instant lateEvening = Instant.parse("2026-11-20T23:30:00Z");
        assertThat(Formats.date(lateEvening, Locale.GERMANY, ZoneId.of("UTC"))).isEqualTo("20.11.2026");
        assertThat(Formats.date(lateEvening, Locale.GERMANY, ZoneId.of("Europe/Berlin"))).isEqualTo("21.11.2026");
        assertThat(Formats.date(lateEvening, Locale.UK, ZoneId.of("Africa/Windhoek"))).isEqualTo("21 Nov 2026");
        // Summer time: 22:30 UTC in July is already the next day in Berlin (UTC+2), not yet in Lisbon (UTC+1).
        Instant summer = Instant.parse("2026-07-14T22:30:00Z");
        assertThat(Formats.date(summer, Locale.GERMANY, ZoneId.of("Europe/Berlin"))).isEqualTo("15.07.2026");
        assertThat(Formats.date(summer, Locale.GERMANY, ZoneId.of("Europe/Lisbon"))).isEqualTo("14.07.2026");
    }

    @Test
    void onlyRegionTimeZonesAreAccepted() {
        assertThat(NotificationSettings.zone("Africa/Windhoek")).contains(ZoneId.of("Africa/Windhoek"));
        assertThat(NotificationSettings.zone("UTC")).contains(ZoneId.of("UTC"));
        assertThat(NotificationSettings.zone("+02:00")).isEmpty();
        assertThat(NotificationSettings.zone("Mars/Olympus_Mons")).isEmpty();
        assertThat(NotificationSettings.zone(null)).isEmpty();
    }

    @Test
    void propertiesDefaultWhatIsNotSet() {
        NotificationProperties defaults = new NotificationProperties(" ", null, 0, null, 0, null, null, null);
        assertThat(defaults.from()).contains("@freightflow.dev");
        assertThat(defaults.sendBatch()).isPositive();
        assertThat(defaults.lease()).isGreaterThan(Duration.ZERO);
        assertThat(defaults.maxAttempts()).isEqualTo(6);
        assertThat(defaults.retention()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void settingsDefaultToEnglishWithoutRecipientsAndWantNothing() {
        NotificationSettings defaults = NotificationSettings.defaults(UUID.randomUUID());
        assertThat(defaults.locale()).isEqualTo(Locale.UK);
        assertThat(defaults.timeZone()).isEqualTo(ZoneId.of("UTC"));
        assertThat(defaults.wants(NotificationKind.SHIPMENT_BOOKED)).isFalse();
        assertThat(defaults.kindCodes()).hasSize(NotificationKind.values().length);
        assertThat(NotificationKind.of("newsletter")).isEmpty();
        assertThat(ConsumedEvent.decimal(null)).isNull();
    }
}
