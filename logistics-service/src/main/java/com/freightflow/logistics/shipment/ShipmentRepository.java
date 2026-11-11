package com.freightflow.logistics.shipment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ShipmentRepository extends JpaRepository<Shipment, UUID> {

    @Query(value = "SELECT nextval('tracking_number_seq')", nativeQuery = true)
    long nextTrackingSequence();

    Optional<Shipment> findByTrackingNumber(String trackingNumber);

    @Query("""
            SELECT s FROM Shipment s
            WHERE (:everything = true OR s.bookingOrganizationId = :org OR s.payerOrganizationId = :org OR s.carrierId = :carrier)
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findFirstPage(
            @Param("everything") boolean everything,
            @Param("org") UUID org,
            @Param("carrier") String carrier,
            Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE (:everything = true OR s.bookingOrganizationId = :org OR s.payerOrganizationId = :org OR s.carrierId = :carrier)
              AND (s.createdAt < :cursorCreatedAt
                   OR (s.createdAt = :cursorCreatedAt AND s.id < :cursorId))
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findPageAfter(
            @Param("everything") boolean everything,
            @Param("org") UUID org,
            @Param("carrier") String carrier,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = :status AND (:everything = true OR s.bookingOrganizationId = :org OR s.payerOrganizationId = :org OR s.carrierId = :carrier)
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findFirstPageByStatus(
            @Param("everything") boolean everything,
            @Param("org") UUID org,
            @Param("carrier") String carrier,
            @Param("status") ShipmentStatus status,
            Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = :status AND (:everything = true OR s.bookingOrganizationId = :org OR s.payerOrganizationId = :org OR s.carrierId = :carrier)
              AND (s.createdAt < :cursorCreatedAt
                   OR (s.createdAt = :cursorCreatedAt AND s.id < :cursorId))
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findPageByStatusAfter(
            @Param("everything") boolean everything,
            @Param("org") UUID org,
            @Param("carrier") String carrier,
            @Param("status") ShipmentStatus status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    /** Booked, authorised shipments with a planned pickup whose hold runs out before the cutoff, soonest first. */
    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = com.freightflow.logistics.shipment.ShipmentStatus.BOOKED
              AND s.paymentStatus = com.freightflow.logistics.shipment.PaymentStatus.AUTHORISED
              AND s.holdId IS NOT NULL AND s.plannedPickupDate IS NOT NULL
              AND s.holdExpiresAt < :cutoff
            ORDER BY s.holdExpiresAt ASC
            """)
    List<Shipment> findBookedWithHoldExpiringBefore(@Param("cutoff") Instant cutoff, Pageable pageable);
}
