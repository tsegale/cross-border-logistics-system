package com.freightflow.logistics.shipment.dto;

import java.util.List;

public record ShipmentPageResponse(
        List<ShipmentResponse> items,
        String nextCursor
) {
}
