package com.freightflow.logistics.dcsa;

import com.freightflow.logistics.outbox.Backoff;
import com.freightflow.logistics.security.Caller;
import com.freightflow.logistics.shipment.ShipmentService;
import com.freightflow.logistics.shipment.dto.RecordTrackingEventRequest;
import com.freightflow.logistics.shipment.dto.TrackingEventResponse;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns stored DCSA events into tracking events (ADR 0037), on every instance, leased so two never take the same
 * event (ADR 0025). An event finds its shipments among the carrier's open ones, the way DCSA T&amp;T 2.2 links them:
 * <ul>
 *   <li>an equipment event by its container ({@code equipmentReference}): every shipment in it, since LCL
 *       consolidations share one; failing that, by its booking references;</li>
 *   <li>a transport event (a vessel's departure or arrival) by its {@code documentReferences} of type BKG, the
 *       carrier's booking reference, and by its transport call: the shipments whose containers this carrier reported
 *       on the same vessel (IMO number) and voyage.</li>
 * </ul>
 * Each shipment gets the milestone for its own origin and destination ({@link DcsaMapping}), recorded as the carrier
 * would record it by hand with {@code dcsa:<eventID>} as its carrier event id. Out-of-order and duplicate events are
 * therefore handled exactly as for every other carrier event (ADR 0009): stored, applied only forwards, skipped
 * milestones recorded, a second delivery a no-op. A transport event that matches nothing yet is retried for a while:
 * the equipment event that links its vessel to a shipment may still be on its way or being processed elsewhere.
 */
@Component
public class DcsaEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DcsaEventProcessor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 8;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Backoff BACKOFF = new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(5));
    private static final Pattern IMO = Pattern.compile("^[0-9]{7}$");
    /** How far back a late equipment event re-queues its vessel's unmatched transport events: a long voyage. */
    private static final Duration REQUEUE_WINDOW = Duration.ofDays(30);
    private static final String OPEN = "status NOT IN ('DELIVERED', 'CANCELLED', 'PAYMENT_FAILED')";

    private final DcsaInbox inbox;
    private final ShipmentService shipments;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meters;
    private final DcsaProperties properties;
    private final Clock clock;

    public DcsaEventProcessor(DcsaInbox inbox, ShipmentService shipments, JdbcTemplate jdbcTemplate,
                              MeterRegistry meters, DcsaProperties properties, Clock clock) {
        this.inbox = inbox;
        this.properties = properties;
        this.shipments = shipments;
        this.jdbcTemplate = jdbcTemplate;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.logistics.dcsa.process-interval:500ms}")
    public void scheduledProcessing() {
        if (properties.processingEnabled()) {
            processDue();
        }
    }

    /** One batch of due events; public so tests drive it instead of the schedule. */
    public void processDue() {
        List<UUID> due;
        try {
            due = inbox.claimDue(BATCH, LEASE);
        } catch (RuntimeException ex) {
            log.warn("DCSA events could not be claimed: {}", ex.toString());
            return;
        }
        due.forEach(this::process);
    }

    /** One stored event; public so tests run it without waiting for the schedule. */
    public void process(UUID id) {
        DcsaInbox.Pending pending = inbox.load(id);
        try {
            String outcome = apply(pending);
            meters.counter("freightflow.dcsa.events.processed", "outcome", outcome).increment();
        } catch (NotMatchedYet ex) {
            if (pending.attempts() >= MAX_ATTEMPTS) {
                inbox.finish(id, "UNMATCHED", null, ex.getMessage());
                meters.counter("freightflow.dcsa.events.processed", "outcome", "unmatched").increment();
                return;
            }
            inbox.retryAt(id, clock.instant().plus(BACKOFF.after(pending.attempts())), ex.getMessage());
        } catch (RuntimeException ex) {
            if (pending.attempts() >= MAX_ATTEMPTS) {
                inbox.finish(id, "FAILED", null, "Out of attempts: " + ex);
                meters.counter("freightflow.dcsa.events.processed", "outcome", "failed").increment();
                log.error("DCSA event {} failed after {} attempts: {}", id, pending.attempts(), ex.toString());
                return;
            }
            Instant retryAt = clock.instant().plus(BACKOFF.after(pending.attempts()));
            inbox.retryAt(id, retryAt, ex.toString());
            log.warn("DCSA event {} not processed (attempt {}), retrying at {}: {}", id, pending.attempts(), retryAt,
                    ex.toString());
        }
    }

    private String apply(DcsaInbox.Pending pending) {
        JsonNode event = JSON.readTree(pending.payload());
        String type = event.path("eventType").asString("");
        Map<UUID, Target> targets = switch (type) {
            case "EQUIPMENT" -> {
                Map<UUID, Target> found = pending.equipmentReference() == null ? new LinkedHashMap<>()
                        : byContainer(pending.carrierId(), pending.equipmentReference());
                yield found.isEmpty() ? byBookingReference(pending.carrierId(), event) : found;
            }
            case "TRANSPORT" -> {
                Map<UUID, Target> found = byBookingReference(pending.carrierId(), event);
                byTransportCall(pending.carrierId(), event).forEach(found::putIfAbsent);
                if (found.isEmpty()) {
                    throw new NotMatchedYet("No shipment of this carrier has a booking reference of the event, or a "
                            + "container reported on vessel " + vessel(event).orElse("(none)") + " voyage "
                            + voyages(event.path("transportCall")));
                }
                yield found;
            }
            default -> Map.of();
        };
        if (!"EQUIPMENT".equals(type) && !"TRANSPORT".equals(type)) {
            inbox.finish(pending.id(), "IGNORED", null, "Only equipment and transport events move a shipment");
            return "ignored";
        }
        if (targets.isEmpty()) {
            inbox.finish(pending.id(), "UNMATCHED", null, "No open shipment of this carrier has the container or a "
                    + "booking reference of the event");
            return "unmatched";
        }

        Caller carrier = new Caller(pending.carrierId(), pending.carrierId(), null, Set.of(Caller.CARRIER), Set.of());
        List<String> applied = new ArrayList<>();
        UUID first = null;
        for (Target shipment : targets.values()) {
            first = first == null ? shipment.id() : first;
            if ("EQUIPMENT".equals(type)) {
                recordTransportCall(pending, shipment, event);
            }
            Optional<DcsaMapping.Milestone> milestone = DcsaMapping.milestone(event, shipment.origin(),
                    shipment.destination());
            if (milestone.isEmpty()) {
                link(pending.id(), shipment, "IGNORED", null);
                continue;
            }
            TrackingEventResponse recorded = shipments.recordTrackingEvent(carrier, shipment.id(),
                    new RecordTrackingEventRequest("dcsa:" + pending.id(), "DCSA_" + code(event),
                            milestone.get().status(), milestone.get().location(), milestone.get().description(),
                            occurredAt(event)), null);
            link(pending.id(), shipment, "APPLIED", recorded.id());
            applied.add(shipment.id() + " now " + recorded.currentShipmentStatus());
        }
        if (applied.isEmpty()) {
            inbox.finish(pending.id(), "IGNORED", first, "No FreightFlow milestone for "
                    + event.path("eventClassifierCode").asString("") + " " + code(event) + " at "
                    + DcsaMapping.location(event) + " on the " + targets.size() + " matched shipment(s)");
            return "ignored";
        }
        inbox.finish(pending.id(), "APPLIED", first, applied.size() + " of " + targets.size()
                + " matched shipment(s) moved: " + String.join(", ", applied));
        return "applied";
    }

    /** How an event found a shipment: recorded with each link. */
    private record Target(UUID id, String origin, String destination, String matchedBy) {
    }

    private Map<UUID, Target> byContainer(String carrierId, String container) {
        return targets("SELECT id, origin_unlocode, destination_unlocode FROM shipments WHERE container_number = ? "
                + "AND carrier_id = ? AND " + OPEN + " ORDER BY created_at", "EQUIPMENT", container, carrierId);
    }

    private Map<UUID, Target> byBookingReference(String carrierId, JsonNode event) {
        Set<String> references = new LinkedHashSet<>();
        for (JsonNode reference : event.path("documentReferences")) {
            String value = reference.path("documentReferenceValue").asString("").trim();
            // The 2.2.0 schema's enum spells it "BKG (Booking)", its example and carriers "BKG": both are a booking.
            String type = reference.path("documentReferenceType").asString("").trim();
            if ((type.equals("BKG") || type.startsWith("BKG ")) && !value.isEmpty()) {
                references.add(value);
            }
        }
        if (references.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return targets("SELECT id, origin_unlocode, destination_unlocode FROM shipments "
                        + "WHERE carrier_booking_reference = ANY (?) AND carrier_id = ? AND " + OPEN + " ORDER BY created_at",
                "BOOKING_REFERENCE", references.toArray(String[]::new), carrierId);
    }

    private Map<UUID, Target> byTransportCall(String carrierId, JsonNode event) {
        Optional<String> vessel = vessel(event);
        String[] voyages = voyages(event.path("transportCall")).toArray(String[]::new);
        if (vessel.isEmpty() || voyages.length == 0) {
            return new LinkedHashMap<>();
        }
        return targets("""
                SELECT s.id, s.origin_unlocode, s.destination_unlocode FROM shipments s
                WHERE s.carrier_id = ? AND s.%s AND EXISTS (
                    SELECT 1 FROM shipment_transport_calls c
                    WHERE c.shipment_id = s.id AND c.carrier_id = s.carrier_id AND c.vessel_imo_number = ?
                      AND (c.export_voyage = ANY (?) OR c.import_voyage = ANY (?)))
                ORDER BY s.created_at
                """.formatted(OPEN), "TRANSPORT_CALL", carrierId, vessel.get(), voyages, voyages);
    }

    private Map<UUID, Target> targets(String sql, String matchedBy, Object... args) {
        Map<UUID, Target> found = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            UUID id = rs.getObject("id", UUID.class);
            found.put(id, new Target(id, rs.getString("origin_unlocode"), rs.getString("destination_unlocode"),
                    matchedBy));
        }, args);
        return found;
    }

    /** The vessel and voyage of an equipment event: what later links the vessel's transport events to the shipment. */
    private void recordTransportCall(DcsaInbox.Pending pending, Target shipment, JsonNode event) {
        JsonNode call = event.path("transportCall");
        Optional<String> vessel = vessel(event);
        String export = voyage(call, "exportVoyageNumber").or(() -> voyage(call, "carrierVoyageNumber")).orElse(null);
        String importVoyage = voyage(call, "importVoyageNumber").orElse(null);
        if (vessel.isEmpty() || (export == null && importVoyage == null)) {
            return;
        }
        String location = call.path("UNLocationCode").asString("");
        Instant now = clock.instant();
        int recorded = jdbcTemplate.update("""
                INSERT INTO shipment_transport_calls (shipment_id, dcsa_event_id, carrier_id, vessel_imo_number,
                                                      export_voyage, import_voyage, un_location_code, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (shipment_id, dcsa_event_id) DO NOTHING
                """, shipment.id(), pending.id(), pending.carrierId(), vessel.get(), export, importVoyage,
                location.isBlank() ? null : location, Timestamp.from(now));
        if (recorded == 1) {
            requeueUnmatchedTransportEvents(pending.carrierId(), vessel.get(),
                    voyages(call).toArray(String[]::new), now);
        }
    }

    /**
     * Transport events of this carrier's vessel and voyage that matched nothing, because they were processed before
     * the equipment event that links the vessel to a shipment: back to PENDING, so they reach it now. A carrier's
     * events need not arrive in order, and a late LOAD must not leave the vessel's departure unmatched for good.
     */
    private void requeueUnmatchedTransportEvents(String carrierId, String vessel, String[] voyages, Instant now) {
        int requeued = jdbcTemplate.update("""
                UPDATE dcsa_events SET status = 'PENDING', processed_at = NULL, next_attempt_at = ?, attempts = 0,
                       outcome = 'Re-queued: a container on its vessel and voyage was reported'
                WHERE status = 'UNMATCHED' AND event_type = 'TRANSPORT' AND carrier_id = ? AND received_at > ?
                  AND payload -> 'transportCall' -> 'vessel' ->> 'vesselIMONumber' = ?
                  AND (payload -> 'transportCall' ->> 'exportVoyageNumber' = ANY (?)
                       OR payload -> 'transportCall' ->> 'importVoyageNumber' = ANY (?)
                       OR payload -> 'transportCall' ->> 'carrierVoyageNumber' = ANY (?))
                """, Timestamp.from(now), carrierId, Timestamp.from(now.minus(REQUEUE_WINDOW)), vessel, voyages,
                voyages, voyages);
        if (requeued > 0) {
            log.info("Re-queued {} unmatched transport event(s) of vessel {} for carrier {}", requeued, vessel,
                    carrierId);
        }
    }

    private void link(UUID event, Target shipment, String outcome, UUID trackingEvent) {
        jdbcTemplate.update("""
                INSERT INTO dcsa_event_shipments (dcsa_event_id, shipment_id, outcome, tracking_event_id, matched_by,
                                                  recorded_at)
                VALUES (?, ?, ?, ?, ?, ?)
                ON CONFLICT (dcsa_event_id, shipment_id) DO NOTHING
                """, event, shipment.id(), outcome, trackingEvent, shipment.matchedBy(), Timestamp.from(clock.instant()));
    }

    private static Optional<String> vessel(JsonNode event) {
        String imo = event.path("transportCall").path("vessel").path("vesselIMONumber").asString("").trim();
        return IMO.matcher(imo).matches() ? Optional.of(imo) : Optional.empty();
    }

    /** The voyage numbers a transport call names; carrierVoyageNumber is deprecated in 2.2 but still sent. */
    private static Set<String> voyages(JsonNode call) {
        Set<String> voyages = new LinkedHashSet<>();
        for (String field : List.of("exportVoyageNumber", "importVoyageNumber", "carrierVoyageNumber")) {
            voyage(call, field).ifPresent(voyages::add);
        }
        return voyages;
    }

    private static Optional<String> voyage(JsonNode call, String field) {
        String value = call.path(field).asString("").trim();
        return value.isEmpty() || value.length() > 50 ? Optional.empty() : Optional.of(value);
    }

    private static String code(JsonNode event) {
        String equipment = event.path("equipmentEventTypeCode").asString("");
        return equipment.isBlank() ? event.path("transportEventTypeCode").asString("") : equipment;
    }

    private static Instant occurredAt(JsonNode event) {
        try {
            return OffsetDateTime.parse(event.path("eventDateTime").asString("")).toInstant();
        } catch (DateTimeParseException ex) {
            throw new DcsaExceptions.MalformedEventException("eventDateTime is not ISO 8601 with an offset");
        }
    }

    /** A transport event no shipment is linked to yet: retried, then UNMATCHED rather than FAILED. */
    private static final class NotMatchedYet extends RuntimeException {
        NotMatchedYet(String message) {
            super(message);
        }
    }
}
