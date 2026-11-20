package com.freightflow.logistics.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * A banking hold event as logistics reads it (docs/events/hold.*.schema.json, ADR 0023): only the fields logistics
 * uses, everything else ignored, so banking can add optional fields without breaking this consumer (ADR 0002).
 */
public record HoldEvent(UUID eventId, String type, int schemaVersion, Instant occurredAt, UUID aggregateId,
                        long sequence, Payload payload) {

    public static final String EXPIRED = "hold.expired";
    public static final String CAPTURED = "hold.captured";
    private static final String SHIPMENT_REFERENCE = "SHIPMENT ";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    public record Payload(Hold hold, String capturedAmount, UUID journalEntryId) {
    }

    public record Hold(UUID holdId, UUID organizationId, String reference, String status, String amount,
                       String currency, Instant expiresAt) {
    }

    /**
     * Reads one record's value. Anything that is not a hold event with an id and a hold is a poison message: retrying
     * cannot fix it, so it goes straight to the dead-letter topic.
     */
    public static HoldEvent parse(String json) {
        HoldEvent event;
        try {
            event = JSON.readValue(json, HoldEvent.class);
        } catch (JacksonException e) {
            throw new PoisonEventException("Not a readable event: " + e.getOriginalMessage(), e);
        }
        if (event == null || event.eventId() == null || event.type() == null || event.payload() == null
                || event.payload().hold() == null || event.payload().hold().holdId() == null) {
            throw new PoisonEventException("Not a hold event: eventId, type and payload.hold.holdId are required");
        }
        return event;
    }

    public UUID holdId() {
        return payload.hold().holdId();
    }

    /** The shipment the hold pays for, if logistics made it: reference {@code SHIPMENT <shipment id>}. */
    public Optional<UUID> shipmentId() {
        String reference = payload.hold().reference();
        if (reference == null || !reference.startsWith(SHIPMENT_REFERENCE)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(reference.substring(SHIPMENT_REFERENCE.length())));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    public BigDecimal capturedAmount() {
        return payload.capturedAmount() == null ? null : new BigDecimal(payload.capturedAmount());
    }
}
