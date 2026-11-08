package com.freightflow.logistics.shipment.dto;

import com.freightflow.logistics.shipment.ShipmentStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

public record RecordTrackingEventRequest(
        @Size(max = 128, message = "carrierEventId must be at most 128 characters")
        String carrierEventId,

        @NotBlank(message = "eventType is required")
        @Size(max = 32, message = "eventType must be at most 32 characters")
        String eventType,

        @NotNull(message = "targetStatus is required")
        ShipmentStatus targetStatus,

        @NotBlank(message = "location is required")
        @Size(min = 5, max = 5, message = "location must be a 5-character UN/LOCODE")
        String location,

        @NotBlank(message = "description is required")
        @Size(max = 300, message = "description must be at most 300 characters")
        String description,

        @NotNull(message = "occurredAt is required")
        Instant occurredAt
) {
}
