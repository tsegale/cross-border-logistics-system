package com.freightflow.logistics.shipment.dto;

import com.freightflow.logistics.security.Actor;
import com.freightflow.logistics.security.Caller;
import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.TransportMode;
import com.freightflow.logistics.shipment.PaymentStatus;
import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record ShipmentResponse(
        UUID id,
        String trackingNumber,
        UUID quoteId,
        String origin,
        String destination,
        TransportMode mode,
        Incoterm incoterm,
        ShipmentStatus status,
        PartyDto shipper,
        PartyDto consignee,
        String payerAccountNumber,
        String goodsDescription,
        BigDecimal declaredValue,
        String declaredValueCurrency,
        BigDecimal quotedTotalAmount,
        String quotedCurrency,
        BigDecimal actualWeightKg,
        BigDecimal volumeCbm,
        BigDecimal chargeableWeightKg,
        LocalDate plannedPickupDate,
        String cancellationReason,
        PaymentDto payment,
        UUID bookingOrganizationId,
        UUID payerOrganizationId,
        String carrierId,
        @io.swagger.v3.oas.annotations.media.Schema(description = "Who booked and who cancelled (ADR 0020); operators and auditors only",
                nullable = true)
        AuditDto audit,
        Instant latestEventAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    /** Where paying for the shipment stands (ADR 0012). failureType is a problem type URI from banking-service. */
    public record PaymentDto(PaymentStatus status, UUID holdId, Instant holdExpiresAt, BigDecimal amount,
                             String currency, String failureType, String failureDetail) {
    }

    public record AuditDto(Actor bookedBy, Actor cancelledBy) {
    }

    /** What this caller sees: who did what only for operators and auditors. */
    public static ShipmentResponse from(Shipment s, Caller caller) {
        ShipmentResponse full = from(s);
        if (caller.seesEverything()) {
            return full;
        }
        return new ShipmentResponse(full.id(), full.trackingNumber(), full.quoteId(), full.origin(), full.destination(),
                full.mode(), full.incoterm(), full.status(), full.shipper(), full.consignee(), full.payerAccountNumber(),
                full.goodsDescription(), full.declaredValue(), full.declaredValueCurrency(), full.quotedTotalAmount(),
                full.quotedCurrency(), full.actualWeightKg(), full.volumeCbm(), full.chargeableWeightKg(),
                full.plannedPickupDate(), full.cancellationReason(), full.payment(), full.bookingOrganizationId(),
                full.payerOrganizationId(), full.carrierId(), null, full.latestEventAt(), full.version(), full.createdAt(),
                full.updatedAt());
    }

    public static ShipmentResponse from(Shipment s) {
        return new ShipmentResponse(
                s.getId(),
                s.getTrackingNumber(),
                s.getQuoteId(),
                s.getOriginUnlocode(),
                s.getDestinationUnlocode(),
                s.getMode(),
                s.getIncoterm(),
                s.getStatus(),
                new PartyDto(s.getShipperName(), s.getShipperAddress(), s.getShipperContact()),
                new PartyDto(s.getConsigneeName(), s.getConsigneeAddress(), s.getConsigneeContact()),
                s.getPayerAccountNumber(),
                s.getGoodsDescription(),
                MoneyRounding.roundToMinorUnits(s.getDeclaredValue(), s.getDeclaredValueCurrency()),
                s.getDeclaredValueCurrency(),
                MoneyRounding.roundToMinorUnits(s.getQuotedTotalAmount(), s.getQuotedCurrency()),
                s.getQuotedCurrency(),
                s.getActualWeightKg(),
                s.getVolumeCbm(),
                s.getChargeableWeightKg(),
                s.getPlannedPickupDate(),
                s.getCancellationReason(),
                new PaymentDto(s.getPaymentStatus(), s.getHoldId(), s.getHoldExpiresAt(),
                        MoneyRounding.roundToMinorUnits(s.getQuotedTotalAmount(), s.getQuotedCurrency()),
                        s.getQuotedCurrency(), s.getPaymentFailureType(), s.getPaymentFailureDetail()),
                s.getBookingOrganizationId(),
                s.getPayerOrganizationId(),
                s.getCarrierId(),
                new AuditDto(s.getBookedBy(), s.getCancelledBy()),
                s.getLatestEventAt(),
                s.getVersion(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
