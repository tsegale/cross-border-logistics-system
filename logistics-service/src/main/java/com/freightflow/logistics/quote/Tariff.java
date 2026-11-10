package com.freightflow.logistics.quote;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "tariffs")
public class Tariff {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "origin_unlocode", length = 5, nullable = false)
    private String originUnlocode;

    @Column(name = "destination_unlocode", length = 5, nullable = false)
    private String destinationUnlocode;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16, nullable = false)
    private TransportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_unit", length = 16, nullable = false)
    private BillingUnit billingUnit;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "rate_per_unit", precision = 19, scale = 4, nullable = false)
    private BigDecimal ratePerUnit;

    @Column(name = "minimum_charge", precision = 19, scale = 4, nullable = false)
    private BigDecimal minimumCharge;

    @Column(name = "fuel_surcharge_percent", precision = 7, scale = 4, nullable = false)
    private BigDecimal fuelSurchargePercent;

    @Column(name = "origin_handling_fee", precision = 19, scale = 4, nullable = false)
    private BigDecimal originHandlingFee;

    @Column(name = "destination_handling_fee", precision = 19, scale = 4, nullable = false)
    private BigDecimal destinationHandlingFee;

    @Column(name = "transit_days_min", nullable = false)
    private int transitDaysMin;

    @Column(name = "transit_days_max", nullable = false)
    private int transitDaysMax;

    protected Tariff() {
    }

    public UUID getId() {
        return id;
    }

    public String getOriginUnlocode() {
        return originUnlocode;
    }

    public String getDestinationUnlocode() {
        return destinationUnlocode;
    }

    public TransportMode getMode() {
        return mode;
    }

    public BillingUnit getBillingUnit() {
        return billingUnit;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getRatePerUnit() {
        return ratePerUnit;
    }

    public BigDecimal getMinimumCharge() {
        return minimumCharge;
    }

    public BigDecimal getFuelSurchargePercent() {
        return fuelSurchargePercent;
    }

    public BigDecimal getOriginHandlingFee() {
        return originHandlingFee;
    }

    public BigDecimal getDestinationHandlingFee() {
        return destinationHandlingFee;
    }

    public int getTransitDaysMin() {
        return transitDaysMin;
    }

    public int getTransitDaysMax() {
        return transitDaysMax;
    }
}
