package com.freightflow.logistics.shipment;

public class PreconditionFailedException extends RuntimeException {

    private final String expectedETag;
    private final String currentETag;

    public PreconditionFailedException(String expectedETag, String currentETag, String message) {
        super(message);
        this.expectedETag = expectedETag;
        this.currentETag = currentETag;
    }

    public String getExpectedETag() {
        return expectedETag;
    }

    public String getCurrentETag() {
        return currentETag;
    }
}
