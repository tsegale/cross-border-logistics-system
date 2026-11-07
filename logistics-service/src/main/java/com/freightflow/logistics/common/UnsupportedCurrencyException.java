package com.freightflow.logistics.common;

import java.util.Set;

public class UnsupportedCurrencyException extends RuntimeException {

    private final String currency;
    private final Set<String> supportedCurrencies;

    public UnsupportedCurrencyException(String currency, Set<String> supportedCurrencies) {
        super("Unsupported currency '" + currency + "'; supported currencies: " + supportedCurrencies);
        this.currency = currency;
        this.supportedCurrencies = supportedCurrencies;
    }

    public String getCurrency() {
        return currency;
    }

    public Set<String> getSupportedCurrencies() {
        return supportedCurrencies;
    }
}
