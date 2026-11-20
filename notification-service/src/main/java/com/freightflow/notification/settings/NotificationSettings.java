package com.freightflow.notification.settings;

import com.freightflow.notification.notify.NotificationKind;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Who gets an organisation's emails, in which language, about what (ADR 0027). An organisation that never saved
 * settings has {@link #defaults}: English, every kind, and no recipients, so nothing is sent until it names some.
 *
 * @param version 0 until first saved; the ETag of the settings
 */
public record NotificationSettings(UUID organizationId, String language, List<String> recipients,
                                   Set<NotificationKind> kinds, long version, Instant updatedAt) {

    public static final Set<String> LANGUAGES = Set.of("de", "en");

    public NotificationSettings {
        recipients = List.copyOf(recipients);
        kinds = Set.copyOf(kinds);
    }

    public static NotificationSettings defaults(UUID organizationId) {
        return new NotificationSettings(organizationId, "en", List.of(), Set.of(NotificationKind.values()), 0, null);
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
