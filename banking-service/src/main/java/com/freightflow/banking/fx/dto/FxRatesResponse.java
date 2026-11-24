package com.freightflow.banking.fx.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Schema(description = "Indicative exchange rates from one base currency. Not a quote: nothing is locked.")
public record FxRatesResponse(
        @Schema(example = "EUR") String base,
        @Schema(description = "When the rates were read from the rate source") Instant asOf,
        @Schema(description = "Where the rates come from", example = "static-configuration") String source,
        List<Rate> rates,
        @Schema(description = "Currencies left out because their reference rate is stale (ADR 0034); a quote or hold "
                + "in them is refused with fx-rates-stale until a fresh rate is confirmed")
        List<String> unavailable
) {

    public record Rate(
            @Schema(description = "Quote currency", example = "NAD") String currency,
            @Schema(description = "Mid-market rate: 1 base = midRate quote currency", example = "20.00000000")
            BigDecimal midRate,
            @Schema(description = "Spread banking applies when it converts", example = "0.005000") BigDecimal spread,
            @Schema(description = "Rate a conversion from base would use: midRate x (1 - spread)", example = "19.90000000")
            BigDecimal customerRate,
            @Schema(description = "Where this rate came from (ADR 0034)", example = "ecb+cma-peg") String source,
            @Schema(description = "The day of the reference rate it came from", nullable = true) LocalDate valueDate
    ) {
    }
}
