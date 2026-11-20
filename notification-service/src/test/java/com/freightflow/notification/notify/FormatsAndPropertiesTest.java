package com.freightflow.notification.notify;

import com.freightflow.notification.config.NotificationProperties;
import com.freightflow.notification.events.ConsumedEvent;
import com.freightflow.notification.settings.NotificationSettings;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
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
        assertThat(Formats.date(Instant.parse("2026-11-20T23:30:00Z"), Locale.GERMANY)).isEqualTo("20.11.2026");
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
        assertThat(defaults.wants(NotificationKind.SHIPMENT_BOOKED)).isFalse();
        assertThat(defaults.kindCodes()).hasSize(NotificationKind.values().length);
        assertThat(NotificationKind.of("newsletter")).isEmpty();
        assertThat(ConsumedEvent.decimal(null)).isNull();
    }
}
