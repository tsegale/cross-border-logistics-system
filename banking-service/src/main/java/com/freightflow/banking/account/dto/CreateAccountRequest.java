package com.freightflow.banking.account.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

@Schema(description = "Request to open a customer ledger account in a supported ISO 4217 currency")
public record CreateAccountRequest(
        @Schema(description = "ISO 4217 3-letter currency code", example = "NAD")
        @NotBlank(message = "currency must not be blank")
        @Size(min = 3, max = 3, message = "currency must be a 3-letter ISO 4217 code")
        String currency,

        @Schema(description = "The owning organisation. A customer leaves it out (or gives its own): the account "
                + "belongs to the organisation in its token. An operator must give it.",
                example = "0193a0e0-0000-7000-8000-00000000a001", nullable = true)
        UUID organizationId
) {
}
