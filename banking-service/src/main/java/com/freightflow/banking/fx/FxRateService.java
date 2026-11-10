package com.freightflow.banking.fx;

import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.ExchangeRateProvider.RateQuote;
import com.freightflow.banking.fx.dto.FxRatesResponse;
import org.springframework.stereotype.Service;

import java.time.Clock;
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
        List<FxRatesResponse.Rate> rates = properties.normalizedCurrencies().stream()
                .filter(currency -> !currency.equals(base))
                .sorted()
                .map(currency -> {
                    RateQuote quote = rateProvider.lookupRate(base, currency);
                    return new FxRatesResponse.Rate(currency, quote.midRate(), quote.spread(), quote.effectiveRate());
                })
                .toList();
        return new FxRatesResponse(base, clock.instant(), rateProvider.sourceName(), rates);
    }
}
