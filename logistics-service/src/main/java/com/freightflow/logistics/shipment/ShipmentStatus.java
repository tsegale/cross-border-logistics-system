package com.freightflow.logistics.shipment;

import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * Explicit shipment lifecycle state machine on the Walvis Bay to Hamburg corridor (ADR 0009):
 * <pre>
 *   AWAITING_PAYMENT → BOOKED → PICKED_UP → AT_ORIGIN_PORT → IN_TRANSIT
 *     → AT_DESTINATION_PORT → CUSTOMS_HOLD ⇄ CUSTOMS_CLEARED
 *     → OUT_FOR_DELIVERY → DELIVERED
 * </pre>
 * plus {@link #CANCELLED} (only before {@link #PICKED_UP}, i.e., from {@link #AWAITING_PAYMENT} or
 * {@link #BOOKED}) and {@link #EXCEPTION} (reachable from any active state).
 */
public enum ShipmentStatus {
    AWAITING_PAYMENT,
    BOOKED,
    PICKED_UP,
    AT_ORIGIN_PORT,
    IN_TRANSIT,
    AT_DESTINATION_PORT,
    CUSTOMS_HOLD,
    CUSTOMS_CLEARED,
    OUT_FOR_DELIVERY,
    DELIVERED,
    CANCELLED,
    EXCEPTION;

    public Set<ShipmentStatus> allowedNextStatuses() {
        return switch (this) {
            case AWAITING_PAYMENT -> Collections.unmodifiableSet(
                    EnumSet.of(BOOKED, CANCELLED, EXCEPTION));
            case BOOKED -> Collections.unmodifiableSet(
                    EnumSet.of(PICKED_UP, CANCELLED, EXCEPTION));
            case PICKED_UP -> Collections.unmodifiableSet(
                    EnumSet.of(AT_ORIGIN_PORT, EXCEPTION));
            case AT_ORIGIN_PORT -> Collections.unmodifiableSet(
                    EnumSet.of(IN_TRANSIT, EXCEPTION));
            case IN_TRANSIT -> Collections.unmodifiableSet(
                    EnumSet.of(AT_DESTINATION_PORT, EXCEPTION));
            case AT_DESTINATION_PORT -> Collections.unmodifiableSet(
                    EnumSet.of(CUSTOMS_HOLD, CUSTOMS_CLEARED, EXCEPTION));
            case CUSTOMS_HOLD -> Collections.unmodifiableSet(
                    EnumSet.of(CUSTOMS_CLEARED, EXCEPTION));
            case CUSTOMS_CLEARED -> Collections.unmodifiableSet(
                    EnumSet.of(CUSTOMS_HOLD, OUT_FOR_DELIVERY, EXCEPTION));
            case OUT_FOR_DELIVERY -> Collections.unmodifiableSet(
                    EnumSet.of(DELIVERED, EXCEPTION));
            case EXCEPTION -> Collections.unmodifiableSet(
                    EnumSet.of(PICKED_UP, AT_ORIGIN_PORT, IN_TRANSIT, AT_DESTINATION_PORT,
                            CUSTOMS_HOLD, CUSTOMS_CLEARED, OUT_FOR_DELIVERY, DELIVERED));
            case DELIVERED, CANCELLED -> Set.of();
        };
    }

    /** The forward path a carrier scans along. CUSTOMS_HOLD sits on it but is optional, so it is never "skipped". */
    private static final List<ShipmentStatus> MILESTONES = List.of(
            BOOKED, PICKED_UP, AT_ORIGIN_PORT, IN_TRANSIT, AT_DESTINATION_PORT,
            CUSTOMS_HOLD, CUSTOMS_CLEARED, OUT_FOR_DELIVERY, DELIVERED);

    /** Position on the milestone path, or -1 for AWAITING_PAYMENT, CANCELLED and EXCEPTION. */
    public int milestoneRank() {
        return MILESTONES.indexOf(this);
    }

    /** The required milestones strictly between this status and {@code target}, in order. */
    public List<ShipmentStatus> milestonesSkippedTo(ShipmentStatus target) {
        int from = milestoneRank();
        int to = target.milestoneRank();
        if (from < 0 || to <= from + 1) {
            return List.of();
        }
        return MILESTONES.subList(from + 1, to).stream()
                .filter(status -> status != CUSTOMS_HOLD)
                .toList();
    }

    /**
     * Whether a carrier event may move a shipment here: any transition the state machine allows, plus any forward
     * move along the milestone path from BOOKED onwards, because carrier feeds miss scans. Never out of a
     * terminal status, and never out of AWAITING_PAYMENT: only the payment moves a shipment to BOOKED.
     */
    public boolean canTrackForwardTo(ShipmentStatus target) {
        if (canTransitionTo(target)) {
            return true;
        }
        int from = milestoneRank();
        return from >= 0 && this != DELIVERED && target.milestoneRank() > from;
    }

    public boolean canTransitionTo(ShipmentStatus target) {
        return allowedNextStatuses().contains(target);
    }

    public void assertCanTransitionTo(ShipmentStatus target) {
        if (!canTransitionTo(target)) {
            List<String> allowedNames = allowedNextStatuses().stream()
                    .map(Enum::name)
                    .toList();
            throw new InvalidShipmentStateTransitionException(this, target, allowedNames);
        }
    }
}
