package com.freightflow.logistics.shipment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrackingEventRepository extends JpaRepository<TrackingEvent, UUID> {

    Optional<TrackingEvent> findByShipmentIdAndCarrierEventId(UUID shipmentId, String carrierEventId);

    List<TrackingEvent> findByShipmentIdOrderByOccurredAtAscIdAsc(UUID shipmentId);
}
