package com.freightflow.banking.transfer.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

@Schema(description = "Request to transfer funds between two ledger accounts")
public record CreateTransferRequest(
        @Schema(description = "Source account number (with MOD 97-10 check digits) or UUID", example = "FF83NAD000001000")
        @NotBlank(message = "sourceAccount must not be blank")
        String sourceAccount,

        @Schema(description = "Target account number (with MOD 97-10 check digits) or UUID", example = "FF14EUR000001001")
        @NotBlank(message = "targetAccount must not be blank")
        String targetAccount,

        @Schema(description = "Transfer amount in the specified currency", example = "2000.00")
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount,

        @Schema(description = "ISO 4217 currency code of amount", example = "NAD")
        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @Schema(description = "Payment or invoice reference", example = "INV-NAWVB-DEHAM-2026-0042")
        @NotBlank(message = "reference must not be blank")
        @Size(max = 160, message = "reference must be at most 160 characters")
        String reference,

        @Schema(description = "FX quote ID (required when sourceAccount and targetAccount have different currencies)",
                example = "01930000-0000-7000-8000-000000002000", nullable = true)
        UUID fxQuoteId
) {
}
