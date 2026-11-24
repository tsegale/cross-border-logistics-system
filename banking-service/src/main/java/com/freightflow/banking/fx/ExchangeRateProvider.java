package com.freightflow.banking.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Source of exchange rates for FX quotes and holds (ADR 0007). {@link com.freightflow.banking.fx.rates.ReferenceExchangeRateProvider}
 * prices from the ECB's daily reference rates (ADR 0034); {@link StaticConfiguredExchangeRateProvider} from a fixed
 * table, for tests that need fixed numbers.
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
            BigDecimal effectiveRate,
            // Where the mid rate came from, which day it is for and, for a stored reference rate, its row (ADR 0034).
            // The NAD/ZAR peg has a source but no day or row.
            String source,
            LocalDate valueDate,
            UUID referenceRateId
    ) {
    }
}
