package com.freightflow.logistics.shipment;

import java.util.List;

/** The outcome of applying one tracking event, with the reason when it was not applied. */
public record TrackingDecision(TrackingOutcome outcome, String reason, List<ShipmentStatus> skipped) {

    public TrackingDecision {
        skipped = List.copyOf(skipped);
    }

    public static TrackingDecision applied(List<ShipmentStatus> skipped) {
        return new TrackingDecision(TrackingOutcome.APPLIED, null, skipped);
    }

    public static TrackingDecision notApplied(TrackingOutcome outcome, String reason) {
        return new TrackingDecision(outcome, reason, List.of());
    }

    public boolean isApplied() {
        return outcome == TrackingOutcome.APPLIED;
    }
}
