package com.freightflow.banking.hold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

@Schema(description = "Capture a hold, fully or in part. A hold is captured at most once; the rest is released.")
public record CaptureHoldRequest(
        @Schema(description = "Amount to capture in the hold's charge currency; omitted means the full amount",
                example = "524.75", nullable = true)
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount,

        @Schema(description = "The part of the captured amount that is duties and taxes collected for the customs "
                + "authority, in the charge currency: credited to CUSTOMS_DISBURSEMENTS, not FREIGHT_REVENUE "
                + "(ADR 0036). Omitted means none", example = "3528.52", nullable = true)
        @DecimalMin(value = "0.00", message = "disbursementAmount must not be negative")
        BigDecimal disbursementAmount
) {
    public CaptureHoldRequest(BigDecimal amount) {
        this(amount, null);
    }
}
