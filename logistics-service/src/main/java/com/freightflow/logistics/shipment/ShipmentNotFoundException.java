package com.freightflow.logistics.shipment;

public class ShipmentNotFoundException extends RuntimeException {

    private final String identifier;

    public ShipmentNotFoundException(String identifier) {
        super("Shipment not found: " + identifier);
        this.identifier = identifier;
    }

    public String getIdentifier() {
        return identifier;
    }
}
