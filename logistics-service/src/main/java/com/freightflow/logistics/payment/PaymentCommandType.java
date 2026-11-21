package com.freightflow.logistics.payment;

/** What a payment command asks banking-service to do with a hold (ADR 0012). */
public enum PaymentCommandType {
    AUTHORISE,
    CAPTURE,
    RELEASE,
    /** Move the hold's expiry later while the shipment waits for pickup (ADR 0011). */
    EXTEND,
    /** A new hold after banking expired the last one while the shipment still waits for pickup (ADR 0028). */
    REAUTHORISE
}
