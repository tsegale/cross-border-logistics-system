package com.freightflow.logistics.quote.dto;

import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.TransportMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateQuoteRequest(
        @NotBlank(message = "origin is required")
        String origin,

        @NotBlank(message = "destination is required")
        String destination,

        @NotNull(message = "mode is required")
        TransportMode mode,

        @NotNull(message = "incoterm is required")
        Incoterm incoterm,

        @NotEmpty(message = "packages must not be empty")
        List<@Valid PackageSpecDto> packages,

        @NotBlank(message = "currency is required")
        String currency
) {
}
