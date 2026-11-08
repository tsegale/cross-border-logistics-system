package com.freightflow.logistics.shipment.dto;

import com.freightflow.logistics.location.Location;
import com.freightflow.logistics.shipment.ShipmentStatus;
import com.freightflow.logistics.shipment.TrackingEvent;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TrackingEventResponse(
        UUID id,
        UUID shipmentId,
        String carrierEventId,
        String eventType,
        ShipmentStatus targetStatus,
        ShipmentStatus currentShipmentStatus,
        String location,
        String locationName,
        String locationTimezone,
        String description,
        Instant occurredAtUtc,
        OffsetDateTime occurredAtLocal,
        Instant recordedAt,
        boolean appliedToStatus,
        boolean duplicateIgnored,
        long shipmentVersion
) {

    public static TrackingEventResponse from(
            TrackingEvent event,
            Location location,
            ShipmentStatus currentShipmentStatus,
            long shipmentVersion,
            boolean duplicateIgnored) {
        OffsetDateTime localTime = OffsetDateTime.ofInstant(event.getOccurredAt(), location.zoneId());
        return new TrackingEventResponse(
                event.getId(),
                event.getShipmentId(),
                event.getCarrierEventId(),
                event.getEventType(),
                event.getTargetStatus(),
                currentShipmentStatus,
                location.getUnlocode(),
                location.getName(),
                location.getIanaTimezone(),
                event.getDescription(),
                event.getOccurredAt(),
                localTime,
                event.getRecordedAt(),
                event.isAppliedToStatus(),
                duplicateIgnored,
                shipmentVersion
        );
    }
}
