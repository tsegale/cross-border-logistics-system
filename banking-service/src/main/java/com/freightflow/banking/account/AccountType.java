package com.freightflow.banking.account;

public enum AccountType {
    CUSTOMER,
    CLEARING,
    FREIGHT_REVENUE,
    FX_POSITION,
    /** Duties and import VAT collected for the customs authority: a liability, never revenue (ADR 0036). */
    CUSTOMS_DISBURSEMENTS;

    public boolean isCustomer() {
        return this == CUSTOMER;
    }

    public boolean isSystem() {
        return this != CUSTOMER;
    }
}
