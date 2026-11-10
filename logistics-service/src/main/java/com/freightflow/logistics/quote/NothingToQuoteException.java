package com.freightflow.logistics.quote;

import java.util.Locale;

/** EXW quoted to the shipper, or DDP quoted to the consignee: that party pays the forwarder for no leg at all. */
public class NothingToQuoteException extends RuntimeException {

    private final Incoterm incoterm;
    private final PayerRole payerRole;

    public NothingToQuoteException(Incoterm incoterm, PayerRole payerRole) {
        super("Under " + incoterm + " the " + payerRole.name().toLowerCase(Locale.ROOT)
                + " pays for no leg of the move, so there is nothing to quote; ask as the other party");
        this.incoterm = incoterm;
        this.payerRole = payerRole;
    }

    public Incoterm getIncoterm() {
        return incoterm;
    }

    public PayerRole getPayerRole() {
        return payerRole;
    }
}
