package com.freightflow.logistics.quote.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PackageSpecDto(
        @NotNull(message = "lengthCm is required")
        @Positive(message = "lengthCm must be positive")
        BigDecimal lengthCm,

        @NotNull(message = "widthCm is required")
        @Positive(message = "widthCm must be positive")
        BigDecimal widthCm,

        @NotNull(message = "heightCm is required")
        @Positive(message = "heightCm must be positive")
        BigDecimal heightCm,

        @NotNull(message = "weightKg is required")
        @Positive(message = "weightKg must be positive")
        BigDecimal weightKg,

        @Min(value = 1, message = "quantity must be at least 1")
        int quantity,

        @Size(max = 200, message = "description must be at most 200 characters")
        String description
) {
}
