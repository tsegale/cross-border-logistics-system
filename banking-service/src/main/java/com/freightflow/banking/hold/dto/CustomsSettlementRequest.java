package com.freightflow.banking.hold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Pay the duties and taxes customs assessed for a captured hold to the authority (ADR 0036). The "
        + "difference from the estimate captured into CUSTOMS_DISBURSEMENTS is charged to or refunded to the payer.")
public record CustomsSettlementRequest(
        @Schema(description = "Duties and import VAT as assessed, in the hold's charge currency", example = "3611.34")
        @NotNull(message = "assessedAmount is required")
        @DecimalMin(value = "0.00", message = "assessedAmount must not be negative")
        BigDecimal assessedAmount,

        @Schema(description = "The authority's reference for the assessment, such as the customs declaration number",
                example = "26DE470100123456E1")
        @NotBlank(message = "authorityReference is required")
        @Size(max = 64, message = "authorityReference must be at most 64 characters")
        String authorityReference
) {
}
