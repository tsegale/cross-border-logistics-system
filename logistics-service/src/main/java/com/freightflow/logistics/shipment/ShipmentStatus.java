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
