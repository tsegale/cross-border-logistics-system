package com.freightflow.banking.fx;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.ExchangeRateProvider.RateQuote;
import com.freightflow.banking.fx.dto.FxRatesResponse;
import com.freightflow.banking.fx.rates.ReferenceRateExceptions.FxRatesStaleException;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

@Service
public class FxRateService {

    private final ExchangeRateProvider rateProvider;
    private final BankingProperties properties;
    private final Clock clock;

    public FxRateService(ExchangeRateProvider rateProvider, BankingProperties properties, Clock clock) {
        this.rateProvider = rateProvider;
        this.properties = properties;
        this.clock = clock;
    }

    public FxRatesResponse indicativeRates(String rawBase) {
        String base = MoneyRounding.validateSupportedCurrency(rawBase, properties.normalizedCurrencies())
                .getCurrencyCode();
        List<FxRatesResponse.Rate> rates = new ArrayList<>();
        List<String> unavailable = new ArrayList<>();
        // Indicative only: a pair whose rate is stale is left out and named, not a failed answer (ADR 0034). The
        // pegged NAD/ZAR is always there; a quote or a hold for a stale pair is refused with fx-rates-stale.
        properties.normalizedCurrencies().stream().filter(currency -> !currency.equals(base)).sorted()
                .forEach(currency -> {
                    try {
                        RateQuote quote = rateProvider.lookupRate(base, currency);
                        rates.add(new FxRatesResponse.Rate(currency, quote.midRate(), quote.spread(),
                                quote.effectiveRate(), quote.source(), quote.valueDate()));
                    } catch (FxRatesStaleException stale) {
                        unavailable.add(currency);
                    }
                });
        return new FxRatesResponse(base, clock.instant(), rateProvider.sourceName(), rates, unavailable);
    }
}
