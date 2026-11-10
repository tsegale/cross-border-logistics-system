package com.freightflow.banking.fx.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Schema(description = "Indicative exchange rates from one base currency. Not a quote: nothing is locked.")
public record FxRatesResponse(
        @Schema(example = "EUR") String base,
        @Schema(description = "When the rates were read from the rate source") Instant asOf,
        @Schema(description = "Where the rates come from", example = "static-configuration") String source,
        List<Rate> rates
) {

    public record Rate(
            @Schema(description = "Quote currency", example = "NAD") String currency,
            @Schema(description = "Mid-market rate: 1 base = midRate quote currency", example = "20.00000000")
            BigDecimal midRate,
            @Schema(description = "Spread banking applies when it converts", example = "0.005000") BigDecimal spread,
            @Schema(description = "Rate a conversion from base would use: midRate x (1 - spread)", example = "19.90000000")
            BigDecimal customerRate
    ) {
    }
}
