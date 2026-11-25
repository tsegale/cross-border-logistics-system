package com.freightflow.logistics.dcsa;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Carrier track and trace in the DCSA standard (ADR 0037).
 *
 * @param replayWindow    a pushed event created longer ago than this is refused as a replay; its eventID is the
 *                        second guard, for anything inside the window
 * @param clockSkew       how far in the future an event's creation time may be
 * @param processInterval how often stored events are turned into tracking events
 * @param processingEnabled whether the scheduled processor runs (default true); tests drive
 *                        {@link DcsaEventProcessor#processDue()} themselves, so a cached test context's schedule does
 *                        not take another test's events
 * @param carriers        per carrier id (the carrier's client id in Keycloak): its subscription
 */
@ConfigurationProperties(prefix = "freightflow.logistics.dcsa")
public record DcsaProperties(Duration replayWindow, Duration clockSkew, Duration processInterval,
                             Boolean processingEnabled, Map<String, Carrier> carriers) {

    public DcsaProperties {
        processingEnabled = processingEnabled == null || processingEnabled;
        replayWindow = replayWindow == null ? Duration.ofHours(24) : replayWindow;
        clockSkew = clockSkew == null ? Duration.ofMinutes(5) : clockSkew;
        processInterval = processInterval == null ? Duration.ofMillis(500) : processInterval;
        carriers = carriers == null ? Map.of() : Map.copyOf(carriers);
    }

    /**
     * @param subscriptionId the DCSA subscription the carrier pushes under (header Subscription-ID)
     * @param secret         the shared secret, Base64 (from a compose secret, ADR 0019)
     * @param mode           PUSH: webhooks only; POLL: also fetch {@code baseUrl}/v2/events on a schedule
     * @param baseUrl        the carrier's track and trace API, for polling
     */
    public record Carrier(UUID subscriptionId, String secret, Mode mode, URI baseUrl) {
        public Carrier {
            mode = mode == null ? Mode.PUSH : mode;
        }
    }

    public enum Mode {
        PUSH, POLL
    }
}
