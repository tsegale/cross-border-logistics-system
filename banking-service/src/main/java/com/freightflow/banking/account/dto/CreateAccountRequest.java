package com.freightflow.banking.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "Request to open a customer ledger account in a supported ISO 4217 currency")
public record CreateAccountRequest(
        @Schema(description = "External customer or company reference", example = "CUST-NAM-WALVIS-001")
        @NotBlank(message = "holderReference must not be blank")
        @Size(max = 120, message = "holderReference must be at most 120 characters")
        String holderReference,

        @Schema(description = "ISO 4217 3-letter currency code", example = "NAD")
        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency
) {
}
