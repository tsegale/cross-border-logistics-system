package com.freightflow.banking.common;

import java.util.Collection;
import java.util.List;

public class UnsupportedCurrencyException extends RuntimeException {

    private final String currency;
    private final List<String> supportedCurrencies;

    public UnsupportedCurrencyException(String currency, Collection<String> supportedCurrencies) {
        super("Unsupported currency '" + currency + "'; supported currencies are "
                + supportedCurrencies.stream().sorted().toList());
        this.currency = currency;
        this.supportedCurrencies = supportedCurrencies.stream().sorted().toList();
    }

    public String getCurrency() {
        return currency;
    }

    public List<String> getSupportedCurrencies() {
        return supportedCurrencies;
    }
}
