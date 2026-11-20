package com.freightflow.notification.settings;

import com.freightflow.notification.notify.NotificationKind;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Who gets an organisation's emails, in which language, about what (ADR 0027). An organisation that never saved
 * settings has {@link #defaults}: English, UTC, every kind, and no recipients, so nothing is sent until it names
 * some.
 *
 * @param timeZone the IANA zone its emails' dates are written in
 * @param version  0 until first saved; the ETag of the settings
 */
public record NotificationSettings(UUID organizationId, String language, ZoneId timeZone, List<String> recipients,
                                   Set<NotificationKind> kinds, long version, Instant updatedAt) {

    public static final Set<String> LANGUAGES = Set.of("de", "en");
    public static final ZoneId DEFAULT_TIME_ZONE = ZoneId.of("UTC");

    public NotificationSettings {
        recipients = List.copyOf(recipients);
        kinds = Set.copyOf(kinds);
    }

    public static NotificationSettings defaults(UUID organizationId) {
        return new NotificationSettings(organizationId, "en", DEFAULT_TIME_ZONE, List.of(),
                Set.of(NotificationKind.values()), 0, null);
    }

    /**
     * An IANA region id such as {@code Europe/Berlin} or {@code Africa/Windhoek}, or {@code UTC}; empty for anything
     * else, fixed offsets included: an offset does not follow daylight saving time.
     */
    public static Optional<ZoneId> zone(String id) {
        if (id == null || !(ZoneId.getAvailableZoneIds().contains(id) || "UTC".equals(id))) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZoneId.of(id));
        } catch (DateTimeException unknown) {
            return Optional.empty();
        }
    }

    public boolean wants(NotificationKind kind) {
        return !recipients.isEmpty() && kinds.contains(kind);
    }

    /** German formats (1.234,56 €, 20.11.2026) for {@code de}, British English for {@code en}. */
    public Locale locale() {
        return "de".equals(language) ? Locale.GERMANY : Locale.UK;
    }

    public List<String> kindCodes() {
        return Arrays.stream(NotificationKind.values()).filter(kinds::contains).map(NotificationKind::code).toList();
    }
}
