package com.freightflow.logistics.events;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.payment.PaymentCommandType;
import com.freightflow.logistics.security.Actor;
import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentStatus;
import com.freightflow.logistics.shipment.TrackingEvent;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;

/**
 * The events logistics publishes (docs/events/, ADR 0023), written to the outbox in the caller's transaction
 * (ADR 0025). All are about a shipment, on {@code freightflow.shipment.v1}, keyed and sequenced by its id, so a
 * consumer sees one shipment's booking, status changes, payment failures and tracking events in order. Shipper and
 * consignee names, addresses and contacts, the goods description, the payer account and free text stay in logistics
 * (ADR 0024).
 */
@Component
public class LogisticsEvents {

    /** Every type logistics publishes, with the current schemaVersion of its schema. */
    public enum Type {
        SHIPMENT_BOOKED("shipment.booked", 1),
        SHIPMENT_STATUS_CHANGED("shipment.status-changed", 1),
        SHIPMENT_PAYMENT_FAILED("shipment.payment-failed", 1),
        TRACKING_EVENT_RECORDED("tracking-event.recorded", 1);

        private final String name;
        private final int schemaVersion;

        Type(String name, int schemaVersion) {
            this.name = name;
            this.schemaVersion = schemaVersion;
        }

        public String typeName() {
            return name;
        }

        public String topic() {
            return EventTopics.SHIPMENT;
        }

        public int schemaVersion() {
            return schemaVersion;
        }

        public static Type of(String name) {
            return Arrays.stream(values()).filter(type -> type.name.equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown event type " + name));
        }
    }

    // ------------------------------------------------------------------ payloads

    public record Money(String amount, String currency) {
    }

    public record ShipmentBooked(UUID shipmentId, String trackingNumber, UUID organizationId, UUID payerOrganizationId,
                                 UUID quoteId, String origin, String destination, String mode, String incoterm,
                                 String status, String paymentStatus, Money quotedTotal, String chargeableWeightKg,
                                 LocalDate plannedPickupDate) {
    }

    public record ShipmentStatusChanged(UUID shipmentId, String trackingNumber, UUID organizationId,
                                        UUID payerOrganizationId, String previousStatus, String status,
                                        String paymentStatus, UUID trackingEventId) {
    }

    public record ShipmentPaymentFailed(UUID shipmentId, String trackingNumber, UUID organizationId,
                                        UUID payerOrganizationId, String command, String problemType, String amount,
                                        String currency) {
    }

    public record TrackingEventRecorded(UUID shipmentId, UUID trackingEventId, String carrierEventId, String eventType,
                                        String targetStatus, String location, Instant occurredAt, String outcome,
                                        String shipmentStatus) {
    }

    // ------------------------------------------------------------------ publishing

    private final EventOutbox outbox;

    public LogisticsEvents(EventOutbox outbox) {
        this.outbox = outbox;
    }

    public void shipmentBooked(Shipment shipment, Actor actor) {
        append(Type.SHIPMENT_BOOKED, shipment, new ShipmentBooked(shipment.getId(), shipment.getTrackingNumber(),
                shipment.getBookingOrganizationId(), shipment.getPayerOrganizationId(), shipment.getQuoteId(),
                shipment.getOriginUnlocode(), shipment.getDestinationUnlocode(), shipment.getMode().name(),
                shipment.getIncoterm().name(), shipment.getStatus().name(), shipment.getPaymentStatus().name(),
                new Money(money(shipment.getQuotedTotalAmount()), shipment.getQuotedCurrency()),
                weight(shipment.getChargeableWeightKg()), shipment.getPlannedPickupDate()), actor);
    }

    /**
     * {@code shipment.status-changed}, if the status did change.
     *
     * @param trackingEventId the tracking event that moved it, or null
     * @param actor           who caused it, or null for an outcome banking reported to the payment relay
     */
    public void statusChanged(Shipment shipment, ShipmentStatus previous, UUID trackingEventId, Actor actor) {
        if (previous == shipment.getStatus()) {
            return;
        }
        append(Type.SHIPMENT_STATUS_CHANGED, shipment, new ShipmentStatusChanged(shipment.getId(),
                shipment.getTrackingNumber(), shipment.getBookingOrganizationId(), shipment.getPayerOrganizationId(),
                previous.name(), shipment.getStatus().name(), shipment.getPaymentStatus().name(), trackingEventId),
                actor);
    }

    /**
     * banking refused a payment command. Only the problem's type URI: its detail can name an account. A refusal
     * without a problem document has the type RFC 9457 gives it, {@code about:blank}.
     */
    public void paymentFailed(Shipment shipment, PaymentCommandType command, String problemType, BigDecimal amount,
                              String currency, Actor actor) {
        String type = problemType != null && problemType.startsWith("https://") ? problemType : "about:blank";
        append(Type.SHIPMENT_PAYMENT_FAILED, shipment, new ShipmentPaymentFailed(shipment.getId(),
                shipment.getTrackingNumber(), shipment.getBookingOrganizationId(), shipment.getPayerOrganizationId(),
                command.name(), type, money(amount), currency), actor);
    }

    public void trackingEventRecorded(Shipment shipment, TrackingEvent event, Actor actor) {
        append(Type.TRACKING_EVENT_RECORDED, shipment, new TrackingEventRecorded(shipment.getId(), event.getId(),
                event.getCarrierEventId(), event.getEventType(), event.getTargetStatus().name(),
                event.getLocationUnlocode(), event.getOccurredAt(), event.getOutcome().name(),
                shipment.getStatus().name()), actor);
    }

    private void append(Type type, Shipment shipment, Object payload, Actor actor) {
        outbox.append(type.topic(), type.typeName(), type.schemaVersion(), shipment.getId(), payload, actor);
    }

    private static String money(BigDecimal amount) {
        return MoneyRounding.toStorageScale(amount).toPlainString();
    }

    /** Kilograms as stored, NUMERIC(12, 3). */
    private static String weight(BigDecimal kg) {
        return kg.setScale(3, RoundingMode.HALF_EVEN).toPlainString();
    }
}
