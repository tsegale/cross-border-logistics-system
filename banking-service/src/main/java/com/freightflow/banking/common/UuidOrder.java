package com.freightflow.banking.common;

import java.util.Comparator;
import java.util.UUID;

/** PostgreSQL's {@code uuid} order: unsigned 128-bit, big-endian. Java's {@code UUID.compareTo} is signed. */
public final class UuidOrder {

    public static final Comparator<UUID> POSTGRES = UuidOrder::compare;

    private UuidOrder() {
    }

    public static int compare(UUID a, UUID b) {
        int high = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
        return high != 0 ? high : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
    }
}
