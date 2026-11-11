package com.freightflow.banking.hold;

/** ACTIVE is the only open status; every other one is final. */
public enum HoldStatus {
    ACTIVE,
    CAPTURED,
    RELEASED,
    EXPIRED
}
