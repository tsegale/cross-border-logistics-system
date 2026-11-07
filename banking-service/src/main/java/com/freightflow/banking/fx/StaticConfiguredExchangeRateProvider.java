package com.freightflow.banking.fx;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Static, configuration-backed exchange rate provider for Phase 1 (not live market data).
 * Respects the 1:1 Common Monetary Area peg between NAD and ZAR.
 */
@Component
public class StaticConfiguredExchangeRateProvider implements ExchangeRateProvider {

    private final BankingProperties properties;

    public StaticConfiguredExchangeRateProvider(BankingProperties properties) {
        this.properties = properties;
    }

    @Override
    public RateQuote lookupRate(String sellCurrency, String buyCurrency) {
        String sell = MoneyRounding.validateSupportedCurrency(
                sellCurrency, properties.normalizedCurrencies()).getCurrencyCode();
        String buy = MoneyRounding.validateSupportedCurrency(
                buyCurrency, properties.normalizedCurrencies()).getCurrencyCode();
        if (sell.equals(buy)) {
            throw new InvalidFxQuoteRequestException("sellCurrency and buyCurrency must differ");
        }

        Map<String, BigDecimal> quotesFromSell = properties.fx().rates().get(sell);
        BigDecimal midRate = quotesFromSell != null ? quotesFromSell.get(buy) : null;
        if (midRate == null || midRate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidFxQuoteRequestException("No static exchange rate configured for " + sell + "/" + buy);
        }

        // Enforce the Common Monetary Area 1:1 peg between NAD and ZAR
        if ((sell.equals("NAD") && buy.equals("ZAR")) || (sell.equals("ZAR") && buy.equals("NAD"))) {
            if (midRate.compareTo(BigDecimal.ONE) != 0) {
                throw new IllegalStateException("CMA peg violated: " + sell + "/" + buy + " midRate must be 1.00000000");
            }
        }

        String pairKey = sell + "-" + buy;
        BigDecimal spread = properties.fx().pairSpreads().getOrDefault(pairKey, properties.fx().defaultSpread());
        BigDecimal scaledMid = midRate.setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
        BigDecimal scaledSpread = spread.setScale(6, MoneyRounding.ROUNDING_MODE);
        BigDecimal effectiveRate = scaledMid.multiply(BigDecimal.ONE.subtract(scaledSpread))
                .setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);

        return new RateQuote(sell, buy, scaledMid, scaledSpread, effectiveRate);
    }
}
