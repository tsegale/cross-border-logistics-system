package com.freightflow.banking.fx.rates;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.ExchangeRateProvider;
import com.freightflow.banking.fx.InvalidFxQuoteRequestException;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Prices from the ECB's daily reference rates (ADR 0034). The ECB publishes ZAR per EUR; NAD is ZAR under the Common
 * Monetary Area's 1:1 peg, so EUR/NAD is the same rate, and NAD/ZAR is 1 with no row behind it. The inverse of a rate
 * is computed at {@link MoneyRounding#RATE_SCALE} with {@code HALF_EVEN}. A rate past the staleness limit is refused
 * with {@code fx-rates-stale}, never priced.
 */
public class ReferenceExchangeRateProvider implements ExchangeRateProvider {

    public static final String PEG_SOURCE = "cma-peg";
    private static final Set<String> RAND_AREA = Set.of("NAD", "ZAR");

    private final ReferenceRateService rates;
    private final BankingProperties properties;

    public ReferenceExchangeRateProvider(ReferenceRateService rates, BankingProperties properties) {
        this.rates = rates;
        this.properties = properties;
    }

    @Override
    public String sourceName() {
        return EcbRates.SOURCE;
    }

    @Override
    public RateQuote lookupRate(String sellCurrency, String buyCurrency) {
        String sell = MoneyRounding.validateSupportedCurrency(sellCurrency, properties.normalizedCurrencies())
                .getCurrencyCode();
        String buy = MoneyRounding.validateSupportedCurrency(buyCurrency, properties.normalizedCurrencies())
                .getCurrencyCode();
        if (sell.equals(buy)) {
            throw new InvalidFxQuoteRequestException("sellCurrency and buyCurrency must differ");
        }
        BigDecimal spread = properties.fx().pairSpreads().getOrDefault(sell + "-" + buy, properties.fx().defaultSpread())
                .setScale(6, MoneyRounding.ROUNDING_MODE);
        if (RAND_AREA.contains(sell) && RAND_AREA.contains(buy)) {
            BigDecimal peg = BigDecimal.ONE.setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
            return quote(sell, buy, peg, spread, PEG_SOURCE, null);
        }
        if (!(ReferenceRateService.BASE.equals(sell) && RAND_AREA.contains(buy))
                && !(RAND_AREA.contains(sell) && ReferenceRateService.BASE.equals(buy))) {
            throw new InvalidFxQuoteRequestException("No reference rate for " + sell + "/" + buy);
        }
        ReferenceRate eurZar = rates.usableEurZar();
        BigDecimal randPerEuro = eurZar.getRate().setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
        BigDecimal mid = ReferenceRateService.BASE.equals(sell)
                ? randPerEuro
                : BigDecimal.ONE.divide(randPerEuro, MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
        String source = buy.equals("NAD") || sell.equals("NAD") ? EcbRates.SOURCE + "+" + PEG_SOURCE : EcbRates.SOURCE;
        return quote(sell, buy, mid, spread, source, eurZar);
    }

    private static RateQuote quote(String sell, String buy, BigDecimal mid, BigDecimal spread, String source,
                                   ReferenceRate row) {
        BigDecimal effective = mid.multiply(BigDecimal.ONE.subtract(spread))
                .setScale(MoneyRounding.RATE_SCALE, MoneyRounding.ROUNDING_MODE);
        return new RateQuote(sell, buy, mid, spread, effective, source,
                row == null ? null : row.getValueDate(), row == null ? null : row.getId());
    }
}
