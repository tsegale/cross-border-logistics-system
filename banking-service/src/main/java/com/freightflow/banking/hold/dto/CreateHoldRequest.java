package com.freightflow.banking.hold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Reserve funds on a customer account for a charge that is captured later")
public record CreateHoldRequest(
        @Schema(description = "Payer account number (MOD 97-10 check digits) or UUID", example = "FF49EUR000001002")
        @NotBlank(message = "payerAccount must not be blank")
        String payerAccount,

        @Schema(description = "Amount to reserve, in the charge currency", example = "524.75")
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount,

        @Schema(description = "ISO 4217 charge currency. If it differs from the payer account's, banking converts at "
                + "its own rate and locks that rate on the hold", example = "EUR")
        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @Schema(description = "What the hold is for", example = "SHIPMENT FF-00010011-0")
        @NotBlank(message = "reference must not be blank")
        @Size(max = 160, message = "reference must be at most 160 characters")
        String reference,

        @Schema(description = "When the hold lapses if not captured; default 7 days, at most 30", nullable = true)
        Instant expiresAt,

        @Schema(description = "The organisation logistics charges for: the payer account must belong to it, or the "
                + "account is not found (ADR 0018)", example = "0193a0e0-0000-7000-8000-00000000a001")
        @NotNull(message = "payerOrganizationId is required")
        UUID payerOrganizationId,

        @Schema(description = "Who booked, as logistics knows them: recorded on the hold (ADR 0020)", nullable = true)
        @Size(max = 64, message = "onBehalfOfSubject must be at most 64 characters")
        String onBehalfOfSubject
) {
}
