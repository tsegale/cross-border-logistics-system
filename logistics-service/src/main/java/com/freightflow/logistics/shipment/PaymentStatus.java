package com.freightflow.logistics.shipment;

/** Where a shipment is in paying for itself (ADR 0012), next to its lifecycle status. */
public enum PaymentStatus {
    /** Booked before Phase 2 and already past AWAITING_PAYMENT. */
    NOT_REQUIRED,
    AUTHORISATION_PENDING,
    AUTHORISED,
    CAPTURE_PENDING,
    CAPTURED,
    RELEASE_PENDING,
    RELEASED,
    FAILED
}
