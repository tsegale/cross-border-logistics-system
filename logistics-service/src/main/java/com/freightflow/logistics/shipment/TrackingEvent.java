package com.freightflow.logistics.shipment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "tracking_events")
public class TrackingEvent {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "shipment_id", nullable = false, updatable = false)
    private UUID shipmentId;

    @Column(name = "carrier_event_id", length = 128, updatable = false)
    private String carrierEventId;

    @Column(name = "event_type", length = 32, nullable = false, updatable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_status", length = 32, nullable = false, updatable = false)
    private ShipmentStatus targetStatus;

    @Column(name = "location_unlocode", length = 5, nullable = false, updatable = false)
    private String locationUnlocode;

    @Column(name = "description", length = 300, nullable = false, updatable = false)
    private String description;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "recorded_at", nullable = false, updatable = false)
    private Instant recordedAt;

    @Column(name = "applied_to_status", nullable = false, updatable = false)
    private boolean appliedToStatus;

    protected TrackingEvent() {
    }

    public static TrackingEvent create(
            UUID shipmentId,
            String carrierEventId,
            String eventType,
            ShipmentStatus targetStatus,
            String locationUnlocode,
            String description,
            Instant occurredAt,
            Instant recordedAt,
            boolean appliedToStatus) {
        TrackingEvent e = new TrackingEvent();
        e.shipmentId = Objects.requireNonNull(shipmentId, "shipmentId");
        e.carrierEventId = carrierEventId != null && !carrierEventId.isBlank() ? carrierEventId.trim() : null;
        e.eventType = Objects.requireNonNull(eventType, "eventType").trim();
        e.targetStatus = Objects.requireNonNull(targetStatus, "targetStatus");
        e.locationUnlocode = Objects.requireNonNull(locationUnlocode, "locationUnlocode");
        e.description = Objects.requireNonNull(description, "description").trim();
        e.occurredAt = Objects.requireNonNull(occurredAt, "occurredAt");
        e.recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        e.appliedToStatus = appliedToStatus;
        return e;
    }

    public UUID getId() {
        return id;
    }

    public UUID getShipmentId() {
        return shipmentId;
    }

    public String getCarrierEventId() {
        return carrierEventId;
    }

    public String getEventType() {
        return eventType;
    }

    public ShipmentStatus getTargetStatus() {
        return targetStatus;
    }

    public String getLocationUnlocode() {
        return locationUnlocode;
    }

    public String getDescription() {
        return description;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    public Instant getRecordedAt() {
        return recordedAt;
    }

    public boolean isAppliedToStatus() {
        return appliedToStatus;
    }
}
