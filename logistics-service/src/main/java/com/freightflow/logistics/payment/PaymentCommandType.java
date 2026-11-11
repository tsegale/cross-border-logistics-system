package com.freightflow.logistics.payment;

/** What a payment command asks banking-service to do with a hold (ADR 0012). */
public enum PaymentCommandType {
    AUTHORISE,
    CAPTURE,
    RELEASE
}
