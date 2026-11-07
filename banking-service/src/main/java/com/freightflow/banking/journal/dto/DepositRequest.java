package com.freightflow.banking.journal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

@Schema(description = "Request to credit a customer account from the currency's CLEARING account")
public record DepositRequest(
        @Schema(description = "Positive deposit amount in minor units", example = "50000.00")
        @NotNull(message = "amount is required")
        @DecimalMin(value = "0.01", message = "amount must be at least 0.01")
        BigDecimal amount,

        @Schema(description = "ISO 4217 currency code matching the account", example = "NAD")
        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @Schema(description = "Audit reference for the deposit", example = "DEP-BANK-WINDHOEK-20261107")
        @NotBlank(message = "reference must not be blank")
        @Size(max = 160, message = "reference must be at most 160 characters")
        String reference
) {
}
