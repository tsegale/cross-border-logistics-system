package com.freightflow.logistics.shipment.dto;

import jakarta.validation.constraints.Size;

public record CancelShipmentRequest(
        @Size(max = 300, message = "reason must be at most 300 characters")
        String reason
) {
}
