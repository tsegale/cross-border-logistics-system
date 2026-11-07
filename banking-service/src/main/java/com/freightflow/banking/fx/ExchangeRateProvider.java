package com.freightflow.banking.fx;

import java.math.BigDecimal;

/**
 * Source of exchange rates for FX quotes (ADR 0007).
 * Phase 1 uses {@link StaticConfiguredExchangeRateProvider}; Phase 6 adds live ECB/reference feeds.
 */
public interface ExchangeRateProvider {

    RateQuote lookupRate(String sellCurrency, String buyCurrency);

    record RateQuote(
            String sellCurrency,
            String buyCurrency,
            BigDecimal midRate,
            BigDecimal spread,
            BigDecimal effectiveRate
    ) {
    }
}
