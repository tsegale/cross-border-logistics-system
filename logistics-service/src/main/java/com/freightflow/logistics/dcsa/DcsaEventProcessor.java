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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Turns stored DCSA events into tracking events (ADR 0037), on every instance, leased so two never take the same
 * event (ADR 0025). An equipment event finds its shipment by the container number among the carrier's shipments, is
 * mapped to a milestone ({@link DcsaMapping}) and recorded as that carrier would record it by hand, with
 * {@code dcsa:<eventID>} as its carrier event id. Out-of-order and duplicate events are therefore handled exactly as
 * for every other carrier event (ADR 0009): stored, applied only forwards, skipped milestones recorded.
 */
@Component
public class DcsaEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(DcsaEventProcessor.class);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int BATCH = 20;
    private static final int MAX_ATTEMPTS = 8;
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final Backoff BACKOFF = new Backoff(Duration.ofSeconds(1), Duration.ofMinutes(5));

    private final DcsaInbox inbox;
    private final ShipmentService shipments;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meters;
    private final Clock clock;

    public DcsaEventProcessor(DcsaInbox inbox, ShipmentService shipments, JdbcTemplate jdbcTemplate,
                              MeterRegistry meters, Clock clock) {
        this.inbox = inbox;
        this.shipments = shipments;
        this.jdbcTemplate = jdbcTemplate;
        this.meters = meters;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.logistics.dcsa.process-interval:500ms}")
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
        if (pending.equipmentReference() == null) {
            inbox.finish(pending.id(), "IGNORED", null, "No equipmentReference: only equipment events are matched to "
                    + "shipments by their container");
            return "ignored";
        }
        Optional<Target> target = target(pending.carrierId(), pending.equipmentReference());
        if (target.isEmpty()) {
            inbox.finish(pending.id(), "UNMATCHED", null, "No shipment of this carrier has the container");
            return "unmatched";
        }
        Target shipment = target.get();
        Optional<DcsaMapping.Milestone> milestone = DcsaMapping.milestone(event, shipment.origin(),
                shipment.destination());
        if (milestone.isEmpty()) {
            inbox.finish(pending.id(), "IGNORED", shipment.id(), "No FreightFlow milestone for "
                    + event.path("eventClassifierCode").asString("") + " " + code(event) + " at "
                    + DcsaMapping.location(event));
            return "ignored";
        }
        Instant occurredAt = occurredAt(event);
        Caller carrier = new Caller(pending.carrierId(), pending.carrierId(), null, Set.of(Caller.CARRIER), Set.of());
        TrackingEventResponse recorded = shipments.recordTrackingEvent(carrier, shipment.id(),
                new RecordTrackingEventRequest("dcsa:" + pending.id(), "DCSA_" + code(event),
                        milestone.get().status(), milestone.get().location(), milestone.get().description(),
                        occurredAt), null);
        inbox.finish(pending.id(), "APPLIED", shipment.id(), "Tracking event " + recorded.id() + ", shipment now "
                + recorded.currentShipmentStatus());
        return "applied";
    }

    private record Target(UUID id, String origin, String destination) {
    }

    private Optional<Target> target(String carrierId, String container) {
        return jdbcTemplate.query("""
                SELECT id, origin_unlocode, destination_unlocode FROM shipments
                WHERE container_number = ? AND carrier_id = ? AND status NOT IN ('DELIVERED', 'CANCELLED', 'PAYMENT_FAILED')
                ORDER BY created_at DESC LIMIT 1
                """, (rs, row) -> new Target(rs.getObject("id", UUID.class), rs.getString("origin_unlocode"),
                rs.getString("destination_unlocode")), container, carrierId).stream().findFirst();
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
}
