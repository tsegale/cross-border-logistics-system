package com.freightflow.banking.fx;

import java.math.BigDecimal;

/**
 * Source of exchange rates for FX quotes (ADR 0007).
 * Phase 1 uses {@link StaticConfiguredExchangeRateProvider}; Phase 6 adds live ECB/reference feeds.
 */
public interface ExchangeRateProvider {

    RateQuote lookupRate(String sellCurrency, String buyCurrency);

    /** A short, stable name for where the rates come from, shown on indicative rates. */
    String sourceName();

    record RateQuote(
            String sellCurrency,
            String buyCurrency,
            BigDecimal midRate,
            BigDecimal spread,
            BigDecimal effectiveRate
    ) {
    }
}
