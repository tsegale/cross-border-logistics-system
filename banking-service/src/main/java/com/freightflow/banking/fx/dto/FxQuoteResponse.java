package com.freightflow.banking.fx.dto;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.fx.FxQuote;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record FxQuoteResponse(
        UUID id,
        String sellCurrency,
        String buyCurrency,
        BigDecimal sellAmount,
        BigDecimal buyAmount,
        BigDecimal midRate,
        BigDecimal spread,
        BigDecimal rate,
        @Schema(description = "Where the rate came from (ADR 0034): ecb, ecb+cma-peg or cma-peg", example = "ecb")
        String rateSource,
        @Schema(description = "The day of the reference rate the quote is priced from", nullable = true)
        LocalDate rateValueDate,
        Instant expiresAt,
        Instant usedAt,
        Instant createdAt
) {

    public static FxQuoteResponse from(FxQuote quote) {
        return new FxQuoteResponse(
                quote.getId(),
                quote.getSellCurrency(),
                quote.getBuyCurrency(),
                MoneyRounding.roundToMinorUnits(quote.getSellAmount(), quote.getSellCurrency()),
                MoneyRounding.roundToMinorUnits(quote.getBuyAmount(), quote.getBuyCurrency()),
                quote.getMidRate(),
                quote.getSpread(),
                quote.getRate(),
                quote.getRateSource(),
                quote.getRateValueDate(),
                quote.getExpiresAt(),
                quote.getUsedAt(),
                quote.getCreatedAt()
        );
    }
}
