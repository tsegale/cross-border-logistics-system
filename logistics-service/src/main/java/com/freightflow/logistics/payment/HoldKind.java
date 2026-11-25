package com.freightflow.logistics.payment;

/**
 * Which of a shipment's holds a payment command is for (ADR 0038): the freight hold, in the quote currency, or the
 * duty hold, in the currency customs is owed in when that is not the freight's (NAD duties into Namibia).
 */
public enum HoldKind {
    FREIGHT,
    DUTIES
}
