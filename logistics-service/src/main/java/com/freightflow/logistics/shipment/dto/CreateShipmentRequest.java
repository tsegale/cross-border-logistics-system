package com.freightflow.logistics.shipment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateShipmentRequest(
        @NotNull(message = "quoteId is required")
        UUID quoteId,

        @NotNull(message = "shipper is required")
        @Valid
        PartyDto shipper,

        @NotNull(message = "consignee is required")
        @Valid
        PartyDto consignee,

        @NotBlank(message = "payerAccountNumber is required")
        String payerAccountNumber,

        @NotBlank(message = "goodsDescription is required")
        @Size(max = 400, message = "goodsDescription must be at most 400 characters")
        String goodsDescription,

        @NotNull(message = "declaredValue is required")
        @Positive(message = "declaredValue must be positive")
        BigDecimal declaredValue,

        String declaredValueCurrency,

        /** The day the cargo is due to be picked up (UTC): the hold must last until then (ADR 0011). */
        @NotNull(message = "plannedPickupDate is required")
        LocalDate plannedPickupDate
) {
}
