package com.freightflow.logistics.shipment;

import org.junit.jupiter.api.Test;

import static com.freightflow.logistics.shipment.ShipmentStatus.AT_DESTINATION_PORT;
import static com.freightflow.logistics.shipment.ShipmentStatus.AT_ORIGIN_PORT;
import static com.freightflow.logistics.shipment.ShipmentStatus.AWAITING_PAYMENT;
import static com.freightflow.logistics.shipment.ShipmentStatus.BOOKED;
import static com.freightflow.logistics.shipment.ShipmentStatus.CANCELLED;
import static com.freightflow.logistics.shipment.ShipmentStatus.CUSTOMS_CLEARED;
import static com.freightflow.logistics.shipment.ShipmentStatus.CUSTOMS_HOLD;
import static com.freightflow.logistics.shipment.ShipmentStatus.DELIVERED;
import static com.freightflow.logistics.shipment.ShipmentStatus.IN_TRANSIT;
import static com.freightflow.logistics.shipment.ShipmentStatus.OUT_FOR_DELIVERY;
import static com.freightflow.logistics.shipment.ShipmentStatus.PICKED_UP;
import static org.assertj.core.api.Assertions.assertThat;

/** Which carrier events may move a shipment, and which milestones a forward skip passes (ADR 0009). */
class TrackingRulesTest {

    @Test
    void forwardSkipFromBookedIsAllowed() {
        assertThat(BOOKED.canTrackForwardTo(IN_TRANSIT)).isTrue();
        assertThat(BOOKED.milestonesSkippedTo(IN_TRANSIT)).containsExactly(PICKED_UP, AT_ORIGIN_PORT);
    }

    @Test
    void customsHoldIsOptionalSoItIsNeverReportedAsSkipped() {
        assertThat(AT_DESTINATION_PORT.milestonesSkippedTo(OUT_FOR_DELIVERY)).containsExactly(CUSTOMS_CLEARED);
    }

    @Test
    void backwardMovesAreNotAllowed() {
        assertThat(AT_DESTINATION_PORT.canTrackForwardTo(AT_ORIGIN_PORT)).isFalse();
    }

    @Test
    void customsCanPutAClearedShipmentBackOnHold() {
        assertThat(CUSTOMS_CLEARED.canTrackForwardTo(CUSTOMS_HOLD)).isTrue();
    }

    @Test
    void onlyThePaymentLeavesAwaitingPaymentForAMilestoneOtherThanBooked() {
        assertThat(AWAITING_PAYMENT.canTrackForwardTo(IN_TRANSIT)).isFalse();
    }

    @Test
    void terminalStatusesAreFinal() {
        assertThat(DELIVERED.canTrackForwardTo(OUT_FOR_DELIVERY)).isFalse();
        assertThat(CANCELLED.canTrackForwardTo(PICKED_UP)).isFalse();
    }

    @Test
    void adjacentMoveSkipsNothing() {
        assertThat(PICKED_UP.milestonesSkippedTo(AT_ORIGIN_PORT)).isEmpty();
        assertThat(IN_TRANSIT.milestonesSkippedTo(DELIVERED))
                .containsExactly(AT_DESTINATION_PORT, CUSTOMS_CLEARED, OUT_FOR_DELIVERY);
    }
}
