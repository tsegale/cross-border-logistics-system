package com.freightflow.logistics.shipment;

import java.util.List;

public class InvalidShipmentStateTransitionException extends RuntimeException {

    private final ShipmentStatus currentStatus;
    private final ShipmentStatus requestedStatus;
    private final List<String> allowedNextStatuses;

    public InvalidShipmentStateTransitionException(
            ShipmentStatus currentStatus,
            ShipmentStatus requestedStatus,
            List<String> allowedNextStatuses) {
        super("Cannot transition shipment from " + currentStatus + " to " + requestedStatus
                + "; allowed next states: " + allowedNextStatuses);
        this.currentStatus = currentStatus;
        this.requestedStatus = requestedStatus;
        this.allowedNextStatuses = List.copyOf(allowedNextStatuses);
    }

    public ShipmentStatus getCurrentStatus() {
        return currentStatus;
    }

    public ShipmentStatus getRequestedStatus() {
        return requestedStatus;
    }

    public List<String> getAllowedNextStatuses() {
        return allowedNextStatuses;
    }
}
