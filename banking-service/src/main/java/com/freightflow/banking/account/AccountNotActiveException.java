package com.freightflow.banking.account;

public class AccountNotActiveException extends RuntimeException {

    private final String accountNumber;
    private final AccountStatus status;

    public AccountNotActiveException(String accountNumber, AccountStatus status) {
        super("Account " + accountNumber + " is " + status + " and cannot accept postings");
        this.accountNumber = accountNumber;
        this.status = status;
    }

    public String getAccountNumber() {
        return accountNumber;
    }

    public AccountStatus getStatus() {
        return status;
    }
}
