package com.freightflow.banking.hold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;

@Schema(description = "Capture a hold, fully or in part. A hold is captured at most once; the rest is released.")
public record CaptureHoldRequest(
        @Schema(description = "Amount to capture in the hold's charge currency; omitted means the full amount",
                example = "524.75", nullable = true)
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount
) {
}
