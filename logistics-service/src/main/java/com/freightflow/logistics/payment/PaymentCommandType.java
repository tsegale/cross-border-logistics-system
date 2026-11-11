package com.freightflow.logistics.payment;

/** What a payment command asks banking-service to do with a hold (ADR 0012). */
public enum PaymentCommandType {
    AUTHORISE,
    CAPTURE,
    RELEASE,
    /** Move the hold's expiry later while the shipment waits for pickup (ADR 0011). */
    EXTEND
}
