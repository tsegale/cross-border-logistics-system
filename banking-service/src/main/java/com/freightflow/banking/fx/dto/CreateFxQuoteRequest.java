package com.freightflow.banking.fx.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record CreateFxQuoteRequest(
        @NotBlank(message = "sellCurrency is required")
        String sellCurrency,

        @NotBlank(message = "buyCurrency is required")
        String buyCurrency,

        @Positive(message = "sellAmount must be positive")
        BigDecimal sellAmount,

        @Positive(message = "buyAmount must be positive")
        BigDecimal buyAmount
) {
}
