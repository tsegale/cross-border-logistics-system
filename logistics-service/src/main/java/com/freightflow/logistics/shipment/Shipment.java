package com.freightflow.logistics.shipment;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.quote.FreightQuote;
import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.TransportMode;
import com.freightflow.logistics.shipment.dto.PartyDto;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "shipments")
public class Shipment {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tracking_number", length = 13, nullable = false, updatable = false)
    private String trackingNumber;

    @Column(name = "quote_id", nullable = false, updatable = false)
    private UUID quoteId;

    @Column(name = "origin_unlocode", length = 5, nullable = false, updatable = false)
    private String originUnlocode;

    @Column(name = "destination_unlocode", length = 5, nullable = false, updatable = false)
    private String destinationUnlocode;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16, nullable = false, updatable = false)
    private TransportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "incoterm", length = 3, nullable = false, updatable = false)
    private Incoterm incoterm;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 32, nullable = false)
    private ShipmentStatus status;

    @Column(name = "shipper_name", length = 160, nullable = false, updatable = false)
    private String shipperName;

    @Column(name = "shipper_address", length = 300, nullable = false, updatable = false)
    private String shipperAddress;

    @Column(name = "shipper_contact", length = 160, nullable = false, updatable = false)
    private String shipperContact;

    @Column(name = "consignee_name", length = 160, nullable = false, updatable = false)
    private String consigneeName;

    @Column(name = "consignee_address", length = 300, nullable = false, updatable = false)
    private String consigneeAddress;

    @Column(name = "consignee_contact", length = 160, nullable = false, updatable = false)
    private String consigneeContact;

    @Column(name = "payer_account_number", length = 16, nullable = false, updatable = false)
    private String payerAccountNumber;

    @Column(name = "goods_description", length = 400, nullable = false, updatable = false)
    private String goodsDescription;

    @Column(name = "declared_value", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal declaredValue;

    @Column(name = "declared_value_currency", length = 3, nullable = false, updatable = false)
    private String declaredValueCurrency;

    @Column(name = "quoted_total_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal quotedTotalAmount;

    @Column(name = "quoted_currency", length = 3, nullable = false, updatable = false)
    private String quotedCurrency;

    @Column(name = "actual_weight_kg", precision = 12, scale = 3, nullable = false, updatable = false)
    private BigDecimal actualWeightKg;

    @Column(name = "volume_cbm", precision = 12, scale = 4, nullable = false, updatable = false)
    private BigDecimal volumeCbm;

    @Column(name = "chargeable_weight_kg", precision = 12, scale = 3, nullable = false, updatable = false)
    private BigDecimal chargeableWeightKg;

    @Column(name = "cancellation_reason", length = 300)
    private String cancellationReason;

    @Column(name = "latest_event_at")
    private Instant latestEventAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Shipment() {
    }

    public static Shipment createFromQuote(
            String trackingNumber,
            FreightQuote quote,
            PartyDto shipper,
            PartyDto consignee,
            String payerAccountNumber,
            String goodsDescription,
            BigDecimal declaredValue,
            String declaredValueCurrency,
            Instant now) {
        Shipment s = new Shipment();
        s.trackingNumber = Objects.requireNonNull(trackingNumber, "trackingNumber");
        s.quoteId = quote.getId();
        s.originUnlocode = quote.getOriginUnlocode();
        s.destinationUnlocode = quote.getDestinationUnlocode();
        s.mode = quote.getMode();
        s.incoterm = quote.getIncoterm();
        s.status = ShipmentStatus.AWAITING_PAYMENT;
        s.shipperName = shipper.name().trim();
        s.shipperAddress = shipper.address().trim();
        s.shipperContact = shipper.contact().trim();
        s.consigneeName = consignee.name().trim();
        s.consigneeAddress = consignee.address().trim();
        s.consigneeContact = consignee.contact().trim();
        s.payerAccountNumber = Objects.requireNonNull(payerAccountNumber, "payerAccountNumber");
        s.goodsDescription = goodsDescription.trim();
        s.declaredValue = MoneyRounding.toStorageScale(declaredValue);
        s.declaredValueCurrency = Objects.requireNonNull(declaredValueCurrency, "declaredValueCurrency");
        s.quotedTotalAmount = quote.getTotalAmount();
        s.quotedCurrency = quote.getCurrency();
        s.actualWeightKg = quote.getActualWeightKg();
        s.volumeCbm = quote.getVolumeCbm();
        s.chargeableWeightKg = quote.getChargeableWeightKg();
        s.createdAt = Objects.requireNonNull(now, "now");
        s.updatedAt = now;
        return s;
    }

    public void cancel(String reason, Instant now) {
        this.status.assertCanTransitionTo(ShipmentStatus.CANCELLED);
        this.status = ShipmentStatus.CANCELLED;
        this.cancellationReason = reason != null && !reason.isBlank() ? reason.trim() : "Cancelled by customer";
        this.updatedAt = Objects.requireNonNull(now, "now");
    }

    /**
     * Applies a tracking event (ADR 0009). An event older than {@code latestEventAt} never moves the status
     * backwards; a forward move along the milestone path is applied even when scans were missed; a backward or
     * disallowed move is not applied. In the last two cases the shipment is left untouched, so recording the event
     * does not bump the version.
     */
    public TrackingDecision applyTrackingEvent(ShipmentStatus targetStatus, Instant occurredAt, Instant now) {
        if (this.latestEventAt != null && occurredAt.isBefore(this.latestEventAt)) {
            return TrackingDecision.notApplied(TrackingOutcome.OUT_OF_ORDER,
                    "Occurred before the latest applied event (" + this.latestEventAt + ")");
        }
        List<ShipmentStatus> skipped = List.of();
        if (this.status != targetStatus) {
            if (!this.status.canTrackForwardTo(targetStatus)) {
                return TrackingDecision.notApplied(TrackingOutcome.REJECTED,
                        "Cannot move from " + this.status + " to " + targetStatus
                                + ": backwards or not allowed (allowed next: " + this.status.allowedNextStatuses() + ")");
            }
            skipped = this.status.milestonesSkippedTo(targetStatus);
            this.status = targetStatus;
        }
        this.latestEventAt = occurredAt;
        this.updatedAt = Objects.requireNonNull(now, "now");
        return TrackingDecision.applied(skipped);
    }

    public UUID getId() {
        return id;
    }

    public String getTrackingNumber() {
        return trackingNumber;
    }

    public UUID getQuoteId() {
        return quoteId;
    }

    public String getOriginUnlocode() {
        return originUnlocode;
    }

    public String getDestinationUnlocode() {
        return destinationUnlocode;
    }

    public TransportMode getMode() {
        return mode;
    }

    public Incoterm getIncoterm() {
        return incoterm;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public String getShipperName() {
        return shipperName;
    }

    public String getShipperAddress() {
        return shipperAddress;
    }

    public String getShipperContact() {
        return shipperContact;
    }

    public String getConsigneeName() {
        return consigneeName;
    }

    public String getConsigneeAddress() {
        return consigneeAddress;
    }

    public String getConsigneeContact() {
        return consigneeContact;
    }

    public String getPayerAccountNumber() {
        return payerAccountNumber;
    }

    public String getGoodsDescription() {
        return goodsDescription;
    }

    public BigDecimal getDeclaredValue() {
        return declaredValue;
    }

    public String getDeclaredValueCurrency() {
        return declaredValueCurrency;
    }

    public BigDecimal getQuotedTotalAmount() {
        return quotedTotalAmount;
    }

    public String getQuotedCurrency() {
        return quotedCurrency;
    }

    public BigDecimal getActualWeightKg() {
        return actualWeightKg;
    }

    public BigDecimal getVolumeCbm() {
        return volumeCbm;
    }

    public BigDecimal getChargeableWeightKg() {
        return chargeableWeightKg;
    }

    public String getCancellationReason() {
        return cancellationReason;
    }

    public Instant getLatestEventAt() {
        return latestEventAt;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
