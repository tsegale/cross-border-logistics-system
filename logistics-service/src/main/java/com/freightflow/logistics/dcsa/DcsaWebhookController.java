package com.freightflow.logistics.dcsa;

import io.micrometer.core.instrument.MeterRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The callback URL carriers push DCSA Track and Trace notifications to (ADR 0037). No bearer token: a carrier proves
 * itself with the Notification-Signature over the body, under the subscription it was given. The request is checked,
 * every event stored once by its eventID, and the carrier answered with 204 at once; turning events into milestones
 * happens afterwards ({@link DcsaEventProcessor}), so a slow database step never makes the carrier time out and
 * resend.
 * <ul>
 *   <li>Unknown carrier, unknown Subscription-ID, missing or wrong signature: 401, nothing stored.</li>
 *   <li>An event created more than {@code replay-window} ago or more than {@code clock-skew} ahead: 400, the whole
 *       notification refused. A replay of a recorded notification is old; inside the window its eventIDs are known
 *       and nothing happens twice.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/dcsa/notifications")
@Tag(name = "Carrier tracking", description = "DCSA Track and Trace 2.2 notifications from carriers")
public class DcsaWebhookController {

    private static final Logger log = LoggerFactory.getLogger(DcsaWebhookController.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final DcsaProperties properties;
    private final DcsaInbox inbox;
    private final MeterRegistry meters;
    private final Clock clock;

    public DcsaWebhookController(DcsaProperties properties, DcsaInbox inbox, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.inbox = inbox;
        this.meters = meters;
        this.clock = clock;
    }

    @PostMapping("/{carrierId}")
    @Operation(summary = "Receive a carrier's signed DCSA event notification (an array of events)")
    public ResponseEntity<Void> receive(
            @PathVariable("carrierId") String carrierId,
            @RequestHeader(value = "Subscription-ID", required = false) String subscriptionHeader,
            @RequestHeader(value = DcsaSignature.HEADER, required = false) String signature,
            @RequestBody(required = false) byte[] body) {
        DcsaProperties.Carrier carrier = properties.carriers().get(carrierId);
        byte[] bytes = body == null ? new byte[0] : body;
        if (carrier == null || carrier.secret() == null || carrier.secret().isBlank() || carrier.subscriptionId() == null
                || !carrier.subscriptionId().toString().equalsIgnoreCase(String.valueOf(subscriptionHeader).trim())
                || !DcsaSignature.verify(carrier.secret(), bytes, signature)) {
            count(carrierId, "signature-invalid");
            // The carrier id is in the path and may be anything a caller typed: logged as known or not, nothing more.
            log.warn("DCSA notification refused: signature or subscription invalid for {} carrier",
                    carrier == null ? "an unknown" : "a known");
            throw new DcsaExceptions.InvalidSignatureException(
                    "The Notification-Signature does not match this subscription's secret");
        }
        List<DcsaInbox.Received> events = parse(bytes);
        Instant now = clock.instant();
        for (DcsaInbox.Received event : events) {
            if (event.createdAt().isBefore(now.minus(properties.replayWindow()))
                    || event.createdAt().isAfter(now.plus(properties.clockSkew()))) {
                count(carrierId, "replay-rejected");
                throw new DcsaExceptions.ReplayRejectedException("Event " + event.eventId() + " was created at "
                        + event.createdAt() + ", outside the accepted window of " + properties.replayWindow()
                        + " back and " + properties.clockSkew() + " ahead");
            }
        }
        int stored = 0;
        for (DcsaInbox.Received event : events) {
            if (inbox.store(carrierId, carrier.subscriptionId(), "PUSH", event)) {
                stored++;
            }
        }
        meters.counter("freightflow.dcsa.events.received", "carrier", carrierId, "channel", "push", "outcome", "stored")
                .increment(stored);
        meters.counter("freightflow.dcsa.events.received", "carrier", carrierId, "channel", "push", "outcome",
                "duplicate").increment(events.size() - stored);
        count(carrierId, "accepted");
        return ResponseEntity.noContent().build();
    }

    /** A page of polled events: an empty array is a page with nothing new. */
    static List<DcsaInbox.Received> parseOrEmpty(String body) {
        if (body == null || body.isBlank() || body.trim().equals("[]")) {
            return List.of();
        }
        return parse(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static List<DcsaInbox.Received> parse(byte[] body) {
        JsonNode tree;
        try {
            tree = JSON.readTree(body);
        } catch (JacksonException ex) {
            throw new DcsaExceptions.MalformedEventException("The notification is not JSON");
        }
        if (tree == null || !tree.isArray() || tree.isEmpty()) {
            throw new DcsaExceptions.MalformedEventException("A DCSA notification is a non-empty array of events");
        }
        List<DcsaInbox.Received> events = new ArrayList<>();
        for (JsonNode event : tree) {
            events.add(DcsaInbox.Received.of(event));
        }
        return events;
    }

    private void count(String carrierId, String outcome) {
        // Only configured carrier ids become label values: a caller cannot create series by typing paths.
        String carrier = properties.carriers().containsKey(carrierId) ? carrierId : "unknown";
        meters.counter("freightflow.dcsa.notifications", "carrier", carrier, "outcome", outcome).increment();
    }
}
