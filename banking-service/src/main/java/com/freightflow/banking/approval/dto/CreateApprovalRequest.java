package com.freightflow.banking.approval.dto;

import com.freightflow.banking.approval.ApprovalKind;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "A deposit, reversal or rate confirmation for a second operator to approve (ADR 0021). A DEPOSIT "
        + "names account, amount, currency and reference; a REVERSAL names journalEntryId and, optionally, reason; a "
        + "RATE_CONFIRMATION names referenceRateId and reason.")
public record CreateApprovalRequest(
        @Schema(description = "What the approval posts", example = "DEPOSIT")
        @NotNull(message = "kind is required")
        ApprovalKind kind,

        @Schema(description = "DEPOSIT: the customer account, by id or account number", example = "FF14EUR000000001",
                nullable = true)
        @Size(max = 64, message = "account must be at most 64 characters")
        String account,

        @Schema(description = "DEPOSIT: positive amount in the currency's minor units", example = "50000.00",
                nullable = true)
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount,

        @Schema(description = "DEPOSIT: ISO 4217 code matching the account", example = "NAD", nullable = true)
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @Schema(description = "DEPOSIT: audit reference, such as the bank statement line", example = "DEP-BANK-WINDHOEK-001",
                nullable = true)
        @Size(max = 160, message = "reference must be at most 160 characters")
        String reference,

        @Schema(description = "REVERSAL: the entry to reverse", nullable = true)
        UUID journalEntryId,

        @Schema(description = "REVERSAL: why; defaults to \"Reversal of <id>\"", example = "Duplicate deposit",
                nullable = true)
        @Size(max = 160, message = "reason must be at most 160 characters")
        String reason,

        @Schema(description = "RATE_CONFIRMATION: the quarantined reference rate to make usable (ADR 0034); reason is "
                + "required with it", nullable = true)
        UUID referenceRateId
) {
}
