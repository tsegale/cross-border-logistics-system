package com.freightflow.banking.approval.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "The checker's note on a decision: optional for an approval, required for a rejection")
public record DecisionRequest(
        @Schema(description = "Why it was approved or rejected", example = "Matches bank statement line 2026-11-19/14",
                nullable = true)
        @Size(max = 500, message = "comment must be at most 500 characters")
        String comment
) {
}
