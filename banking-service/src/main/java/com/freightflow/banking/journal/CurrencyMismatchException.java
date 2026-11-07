package com.freightflow.banking.journal;

public class CurrencyMismatchException extends RuntimeException {

    private final String expectedCurrency;
    private final String actualCurrency;

    public CurrencyMismatchException(String expectedCurrency, String actualCurrency, String message) {
        super(message);
        this.expectedCurrency = expectedCurrency;
        this.actualCurrency = actualCurrency;
    }

    public String getExpectedCurrency() {
        return expectedCurrency;
    }

    public String getActualCurrency() {
        return actualCurrency;
    }
}
