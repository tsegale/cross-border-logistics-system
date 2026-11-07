package com.freightflow.logistics.shipment;

public class InvalidTrackingNumberException extends RuntimeException {

    private final String trackingNumber;

    public InvalidTrackingNumberException(String trackingNumber, String message) {
        super(message);
        this.trackingNumber = trackingNumber;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }
}
