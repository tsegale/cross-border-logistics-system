package com.freightflow.banking.fx.dto;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.fx.FxQuote;

import java.math.BigDecimal;
import java.time.Instant;
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
                quote.getExpiresAt(),
                quote.getUsedAt(),
                quote.getCreatedAt()
        );
    }
}
