package com.freightflow.logistics.quote;

public enum TransportMode {
    SEA_LCL(BillingUnit.WM_TON),
    AIR(BillingUnit.KG);

    private final BillingUnit defaultBillingUnit;

    TransportMode(BillingUnit defaultBillingUnit) {
        this.defaultBillingUnit = defaultBillingUnit;
    }

    public BillingUnit defaultBillingUnit() {
        return defaultBillingUnit;
    }
}
