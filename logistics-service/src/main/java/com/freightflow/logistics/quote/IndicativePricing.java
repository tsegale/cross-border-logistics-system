package com.freightflow.logistics.quote;

import com.freightflow.logistics.banking.BankingApi;
import com.freightflow.logistics.banking.FxRates;
import com.freightflow.logistics.common.MoneyRounding;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

/**
 * Shows a quote's total in another currency, using banking-service's rates (banking owns FX). The figure is
 * indicative: the customer is charged in the tariff currency and banking fixes the rate when it authorises the
 * payment. If banking cannot be reached the quote is still issued, without the figure.
 */
@Component
public class IndicativePricing {

    private static final Logger log = LoggerFactory.getLogger(IndicativePricing.class);

    private final BankingApi banking;

    public IndicativePricing(BankingApi banking) {
        this.banking = banking;
    }

    public record Indicative(BigDecimal totalAmount, BigDecimal rate, Instant asOf, String source) {
    }

    /**
     * The amount of {@code payCurrency} a payer would need for {@code amount} of {@code chargeCurrency}, at
     * banking's customer rate from {@code payCurrency} (1 payCurrency = rate chargeCurrency).
     */
    public Optional<Indicative> convert(BigDecimal amount, String chargeCurrency, String payCurrency) {
        try {
            FxRates rates = banking.fxRates(payCurrency);
            Optional<FxRates.Rate> rate = rates == null ? Optional.empty() : rates.to(chargeCurrency);
            if (rate.isEmpty() || rate.get().customerRate() == null || rate.get().customerRate().signum() <= 0) {
                log.warn("banking-service returned no {} -> {} rate; quote issued without an indicative total",
                        payCurrency, chargeCurrency);
                return Optional.empty();
            }
            BigDecimal customerRate = rate.get().customerRate();
            BigDecimal converted = amount.divide(customerRate, MoneyRounding.STORAGE_SCALE + 4, MoneyRounding.ROUNDING_MODE);
            return Optional.of(new Indicative(
                    MoneyRounding.normalizeCustomerAmount(converted, payCurrency),
                    customerRate,
                    rates.asOf(),
                    rates.source()));
        } catch (RestClientException ex) {
            log.warn("Indicative {} total unavailable, banking-service did not answer: {}", payCurrency, ex.getMessage());
            return Optional.empty();
        }
    }
}
