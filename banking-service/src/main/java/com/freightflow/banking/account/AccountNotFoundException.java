package com.freightflow.banking.account;

public class AccountNotFoundException extends RuntimeException {

    private final String identifier;

    public AccountNotFoundException(Object identifier) {
        super("Account '" + identifier + "' was not found");
        this.identifier = String.valueOf(identifier);
    }

    public String getIdentifier() {
        return identifier;
    }
}
