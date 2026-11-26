package com.freightflow.notification.settings;

import com.freightflow.notification.common.InvalidRequestException;
import com.freightflow.notification.notify.NotificationKind;
import com.freightflow.notification.security.Caller;
import com.freightflow.notification.settings.SettingsExceptions.InvalidSettingsException;
import com.freightflow.notification.settings.SettingsExceptions.SettingsChangedException;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * An organisation's own notification settings (ADR 0027). Only a customer, and only for the organisation in its token
 * (ADR 0018): the organisation is never taken from the request. Optimistic concurrency with {@code ETag} and an
 * optional {@code If-Match}.
 */
@RestController
@RequestMapping("/api/v1/notification-settings")
public class SettingsController {

    private final SettingsStore store;
    private final Clock clock;

    public SettingsController(SettingsStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public record SettingsRequest(
            @NotBlank String language,
            @Size(max = 64) String timeZone,
            @NotNull @Size(max = 10, message = "at most 10 recipients") List<@NotBlank @Email @Size(max = 320) String> recipients,
            @NotNull List<@NotBlank String> kinds) {
    }

    public record SettingsResponse(UUID organizationId, String language, String timeZone, List<String> recipients,
                                   List<String> kinds, long version, Instant updatedAt) {

        static SettingsResponse of(NotificationSettings settings) {
            return new SettingsResponse(settings.organizationId(), settings.language(), settings.timeZone().getId(),
                    settings.recipients(),
                    settings.kindCodes(), settings.version(), settings.updatedAt());
        }
    }

    @GetMapping
    public ResponseEntity<SettingsResponse> get(Caller caller) {
        NotificationSettings settings = store.find(caller.requireOrganization());
        return ResponseEntity.ok().eTag(etag(settings.version())).body(SettingsResponse.of(settings));
    }

    @PutMapping
    public ResponseEntity<SettingsResponse> put(Caller caller, @Valid @RequestBody SettingsRequest request,
                                                @RequestHeader(value = HttpHeaders.IF_MATCH, required = false)
                                                String ifMatch) {
        UUID organization = caller.requireOrganization();
        String language = request.language().trim().toLowerCase(Locale.ROOT);
        if (!NotificationSettings.LANGUAGES.contains(language)) {
            throw new InvalidSettingsException("language must be one of " + NotificationSettings.LANGUAGES);
        }
        List<NotificationKind> kinds = new ArrayList<>();
        for (String code : new LinkedHashSet<>(request.kinds())) {
            kinds.add(NotificationKind.of(code.trim()).orElseThrow(() -> new InvalidSettingsException(
                    "Unknown kind '" + code + "'; known: " + java.util.Arrays.stream(NotificationKind.values())
                            .map(NotificationKind::code).toList())));
        }
        List<String> recipients = request.recipients().stream().map(String::trim)
                .map(address -> address.toLowerCase(Locale.ROOT)).distinct().toList();

        NotificationSettings existing = store.find(organization);
        // Optional: a client that does not send it keeps the zone saved before (UTC on a first save).
        ZoneId timeZone = request.timeZone() == null ? existing.timeZone()
                : NotificationSettings.zone(request.timeZone().trim()).orElseThrow(() -> new InvalidSettingsException(
                        "timeZone must be an IANA time zone such as Europe/Berlin or Africa/Windhoek"));

        long current = existing.version();
        long expected = ifMatch == null ? current : version(ifMatch);
        if (expected != current) {
            throw new SettingsChangedException(etag(expected), etag(current));
        }
        store.save(organization, language, timeZone, recipients, kinds, expected, caller.actor(), clock.instant())
                .orElseThrow(() -> new SettingsChangedException(etag(expected), etag(store.find(organization).version())));
        NotificationSettings saved = store.find(organization);
        return ResponseEntity.ok().eTag(etag(saved.version())).body(SettingsResponse.of(saved));
    }

    private static String etag(long version) {
        return "\"" + version + "\"";
    }

    private static long version(String ifMatch) {
        try {
            return Long.parseLong(ifMatch.trim().replace("W/", "").replace("\"", ""));
        } catch (NumberFormatException e) {
            throw new InvalidRequestException("If-Match must be the ETag of the settings, e.g. \"3\"");
        }
    }
}
