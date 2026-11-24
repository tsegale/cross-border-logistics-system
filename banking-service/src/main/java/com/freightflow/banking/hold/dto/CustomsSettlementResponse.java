package com.freightflow.banking.hold.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "A customs settlement: the assessed amount paid to the authority, and the difference from the "
        + "estimate charged (positive) or refunded (negative) to the payer")
public record CustomsSettlementResponse(
        UUID id,
        UUID holdId,
        String currency,
        BigDecimal estimate,
        BigDecimal assessed,
        @Schema(description = "assessed - estimate, in the charge currency") BigDecimal difference,
        @Schema(description = "The difference in the payer's currency, at the hold's locked rate") BigDecimal payerDifference,
        String payerCurrency,
        String authorityReference,
        UUID journalEntryId,
        Instant createdAt
) {
}
