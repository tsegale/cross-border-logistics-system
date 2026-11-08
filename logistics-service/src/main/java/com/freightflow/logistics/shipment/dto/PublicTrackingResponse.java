package com.freightflow.logistics.shipment.dto;

import com.freightflow.logistics.location.Location;
import com.freightflow.logistics.quote.TransportMode;
import com.freightflow.logistics.shipment.ShipmentStatus;
import com.freightflow.logistics.shipment.TrackingEvent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Public tracking view returned by {@code GET /api/v1/shipments/track/{trackingNumber}}.
 * Deliberately omits customer details ({@code shipper}, {@code consignee}, {@code payerAccountNumber},
 * {@code declaredValue}, {@code quotedTotalAmount}) and formats every event timestamp in both UTC
 * and the event location's IANA time zone.
 */
public record PublicTrackingResponse(
        String trackingNumber,
        PublicLocationDto origin,
        PublicLocationDto destination,
        TransportMode mode,
        ShipmentStatus status,
        Instant latestEventAt,
        List<PublicTrackingEventDto> events
) {

    public record PublicLocationDto(
            String unlocode,
            String name,
            String countryCode,
            String timezone
    ) {
        public static PublicLocationDto from(Location loc) {
            return new PublicLocationDto(
                    loc.getUnlocode(),
                    loc.getName(),
                    loc.getCountryCode(),
                    loc.getIanaTimezone()
            );
        }
    }

    public record PublicTrackingEventDto(
            UUID id,
            String eventType,
            ShipmentStatus targetStatus,
            String location,
            String locationName,
            String locationTimezone,
            String description,
            Instant occurredAtUtc,
            OffsetDateTime occurredAtLocal,
            boolean appliedToStatus
    ) {
        public static PublicTrackingEventDto from(TrackingEvent event, Location loc) {
            return new PublicTrackingEventDto(
                    event.getId(),
                    event.getEventType(),
                    event.getTargetStatus(),
                    loc.getUnlocode(),
                    loc.getName(),
                    loc.getIanaTimezone(),
                    event.getDescription(),
                    event.getOccurredAt(),
                    OffsetDateTime.ofInstant(event.getOccurredAt(), loc.zoneId()),
                    event.isAppliedToStatus()
            );
        }
    }
}
