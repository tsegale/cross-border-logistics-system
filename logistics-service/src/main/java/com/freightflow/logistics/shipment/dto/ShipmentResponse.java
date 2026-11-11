package com.freightflow.logistics.shipment.dto;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.TransportMode;
import com.freightflow.logistics.shipment.PaymentStatus;
import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentStatus;

import java.math.BigDecimal;
import java.time.Instant;
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
        String cancellationReason,
        PaymentDto payment,
        Instant latestEventAt,
        long version,
        Instant createdAt,
        Instant updatedAt
) {

    /** Where paying for the shipment stands (ADR 0012). failureType is a problem type URI from banking-service. */
    public record PaymentDto(PaymentStatus status, UUID holdId, BigDecimal amount, String currency,
                             String failureType, String failureDetail) {
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
                s.getCancellationReason(),
                new PaymentDto(s.getPaymentStatus(), s.getHoldId(),
                        MoneyRounding.roundToMinorUnits(s.getQuotedTotalAmount(), s.getQuotedCurrency()),
                        s.getQuotedCurrency(), s.getPaymentFailureType(), s.getPaymentFailureDetail()),
                s.getLatestEventAt(),
                s.getVersion(),
                s.getCreatedAt(),
                s.getUpdatedAt()
        );
    }
}
