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
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findFirstPage(Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.createdAt < :cursorCreatedAt
               OR (s.createdAt = :cursorCreatedAt AND s.id < :cursorId)
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findPageAfter(
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = :status
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findFirstPageByStatus(
            @Param("status") ShipmentStatus status,
            Pageable pageable);

    @Query("""
            SELECT s FROM Shipment s
            WHERE s.status = :status
              AND (s.createdAt < :cursorCreatedAt
                   OR (s.createdAt = :cursorCreatedAt AND s.id < :cursorId))
            ORDER BY s.createdAt DESC, s.id DESC
            """)
    List<Shipment> findPageByStatusAfter(
            @Param("status") ShipmentStatus status,
            @Param("cursorCreatedAt") Instant cursorCreatedAt,
            @Param("cursorId") UUID cursorId,
            Pageable pageable);
}
