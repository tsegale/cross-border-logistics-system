package com.freightflow.logistics.shipment;

public class InvalidAccountNumberException extends RuntimeException {

    private final String accountNumber;

    public InvalidAccountNumberException(String accountNumber, String message) {
        super(message);
        this.accountNumber = accountNumber;
    }

    public String getAccountNumber() {
        return accountNumber;
    }
}
