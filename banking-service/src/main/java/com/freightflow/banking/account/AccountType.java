package com.freightflow.banking.account;

public enum AccountType {
    CUSTOMER,
    CLEARING,
    FREIGHT_REVENUE,
    FX_POSITION;

    public boolean isCustomer() {
        return this == CUSTOMER;
    }

    public boolean isSystem() {
        return this != CUSTOMER;
    }
}
