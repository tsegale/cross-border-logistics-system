package com.freightflow.logistics.payment;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentCommandRepository extends JpaRepository<PaymentCommand, UUID> {

    @Query("SELECT c FROM PaymentCommand c WHERE c.status = :status ORDER BY c.updatedAt ASC")
    List<PaymentCommand> findByStatus(@Param("status") PaymentCommandStatus status, Pageable page);

    List<PaymentCommand> findByShipmentIdOrderByCreatedAtAsc(UUID shipmentId);

    Optional<PaymentCommand> findByShipmentIdAndCommand(UUID shipmentId, PaymentCommandType command);
}
