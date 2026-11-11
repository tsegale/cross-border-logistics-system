package com.freightflow.logistics.payment;

import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Keeps the holds of booked shipments alive until pickup (ADR 0011). It only writes EXTEND commands; the relay sends
 * them. Safe on every instance at once: a shipment can have one open extension only (a partial unique index), so a
 * second instance's insert is a no-op.
 */
@Component
public class HoldRenewalJob {

    private static final Logger log = LoggerFactory.getLogger(HoldRenewalJob.class);

    private final ShipmentRepository shipments;
    private final PaymentCommands outbox;
    private final HoldExpiryPolicy policy;
    private final Clock clock;

    public HoldRenewalJob(ShipmentRepository shipments, PaymentCommands outbox, HoldExpiryPolicy policy, Clock clock) {
        this.shipments = shipments;
        this.outbox = outbox;
        this.policy = policy;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${freightflow.logistics.payments.hold-expiry.renewal-interval:1h}",
            initialDelayString = "${freightflow.logistics.payments.hold-expiry.renewal-interval:1h}")
    public void scheduledRenewal() {
        if (policy.properties().renewalEnabled()) {
            renewDue();
        }
    }

    /** Writes an EXTEND command for every booked shipment whose hold is about to run out. Returns how many. */
    @Transactional
    public int renewDue() {
        Instant now = clock.instant();
        List<Shipment> due = shipments.findBookedWithHoldExpiringBefore(now.plus(policy.properties().renewBefore()),
                PageRequest.of(0, policy.properties().renewalBatch()));
        int enqueued = 0;
        for (Shipment shipment : due) {
            var target = policy.renewal(shipment.getPlannedPickupDate(), shipment.getHoldExpiresAt(), now);
            if (target.isPresent() && outbox.enqueueExtension(shipment.getId(), shipment.getHoldId(),
                    shipment.getQuotedTotalAmount(), shipment.getQuotedCurrency(), shipment.getPayerAccountNumber(),
                    target.get())) {
                enqueued++;
                log.info("Hold {} of shipment {} expires at {}; extension to {} queued", shipment.getHoldId(),
                        shipment.getId(), shipment.getHoldExpiresAt(), target.get());
            }
        }
        return enqueued;
    }
}
