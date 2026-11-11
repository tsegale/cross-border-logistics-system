package com.freightflow.logistics.payment;

/**
 * PENDING: due for the relay (also while it waits for a retry). SUCCEEDED and FAILED are final. PARKED: out of
 * attempts with an unknown outcome; an operator retries it.
 */
public enum PaymentCommandStatus {
    PENDING,
    SUCCEEDED,
    FAILED,
    PARKED
}
