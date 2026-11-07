package com.freightflow.banking.account;

import java.math.BigDecimal;

public class InsufficientFundsException extends RuntimeException {

    private final String accountNumber;
    private final String currency;
    private final BigDecimal availableBalance;
    private final BigDecimal requestedAmount;

    public InsufficientFundsException(String accountNumber, String currency,
                                      BigDecimal availableBalance, BigDecimal requestedAmount) {
        super("Account " + accountNumber + " has insufficient funds: available "
                + availableBalance + " " + currency + ", requested " + requestedAmount + " " + currency);
        this.accountNumber = accountNumber;
        this.currency = currency;
        this.availableBalance = availableBalance;
        this.requestedAmount = requestedAmount;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getAvailableBalance() {
        return availableBalance;
    }

    public BigDecimal getRequestedAmount() {
        return requestedAmount;
    }
}
