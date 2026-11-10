package com.freightflow.logistics.shipment;

/** What a tracking event did to its shipment. Every event is stored, whatever the outcome (ADR 0009). */
public enum TrackingOutcome {
    /** The shipment moved to the event's status (possibly skipping milestones the carrier never scanned). */
    APPLIED,
    /** The event happened before the latest applied event: history only, the status never moves backwards. */
    OUT_OF_ORDER,
    /** The move is backwards or not allowed from the current status: history only. */
    REJECTED
}
