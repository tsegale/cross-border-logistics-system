package com.freightflow.notification.events;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/**
 * A shipment or hold event as notification-service reads it (docs/events/, ADR 0023): the envelope and the payload
 * fields any of its emails use, everything else ignored, so producers can add optional fields without breaking it
 * (ADR 0002). One record for both topics; each type fills the fields its schema has.
 */
public record ConsumedEvent(UUID eventId, String type, int schemaVersion, Instant occurredAt, UUID aggregateId,
                            long sequence, Payload payload) {

    private static final String SHIPMENT_REFERENCE = "SHIPMENT ";
    private static final JsonMapper JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    /** Shipment payloads (shipment.*) and hold payloads (hold.*: everything under {@code hold}). */
    public record Payload(UUID shipmentId, String trackingNumber, UUID organizationId, UUID payerOrganizationId,
                          String origin, String destination, String status, String previousStatus,
                          String paymentStatus, Money quotedTotal, LocalDate plannedPickupDate, String command,
                          String problemType, String amount, String currency, Hold hold, String capturedAmount) {
    }

    public record Money(String amount, String currency) {
    }

    public record Hold(UUID holdId, UUID organizationId, String reference, String status, String amount,
                       String currency, String payerAmount, String payerCurrency, Instant expiresAt) {
    }

    /** Anything without an id, a type and a payload is poison: no retry can fix it (ADR 0026). */
    public static ConsumedEvent parse(String json) {
        ConsumedEvent event;
        try {
            event = JSON.readValue(json, ConsumedEvent.class);
        } catch (JacksonException e) {
            throw new PoisonEventException("Not a readable event: " + e.getOriginalMessage(), e);
        }
        if (event == null || event.eventId() == null || event.type() == null || event.payload() == null
                || event.sequence() < 1) {
            throw new PoisonEventException("Not an event: eventId, type, sequence and payload are required");
        }
        return event;
    }

    /** For hold events: the shipment the hold pays for, if logistics made it ({@code SHIPMENT <id>}). */
    public Optional<UUID> holdShipmentId() {
        if (payload.hold() == null || payload.hold().reference() == null
                || !payload.hold().reference().startsWith(SHIPMENT_REFERENCE)) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(payload.hold().reference().substring(SHIPMENT_REFERENCE.length())));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    public static BigDecimal decimal(String value) {
        return value == null ? null : new BigDecimal(value);
    }
}
