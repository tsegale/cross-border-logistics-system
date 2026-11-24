package com.freightflow.logistics.quote;

import com.freightflow.logistics.common.MoneyRounding;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "freight_quotes")
public class FreightQuote {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "origin_unlocode", length = 5, nullable = false, updatable = false)
    private String originUnlocode;

    @Column(name = "destination_unlocode", length = 5, nullable = false, updatable = false)
    private String destinationUnlocode;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", length = 16, nullable = false, updatable = false)
    private TransportMode mode;

    @Enumerated(EnumType.STRING)
    @Column(name = "incoterm", length = 3, nullable = false, updatable = false)
    private Incoterm incoterm;

    @Enumerated(EnumType.STRING)
    @Column(name = "payer_role", length = 16, nullable = false, updatable = false)
    private PayerRole payerRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_unit", length = 16, nullable = false, updatable = false)
    private BillingUnit billingUnit;

    @Column(name = "package_count", nullable = false, updatable = false)
    private int packageCount;

    @Column(name = "actual_weight_kg", precision = 12, scale = 3, nullable = false, updatable = false)
    private BigDecimal actualWeightKg;

    @Column(name = "volume_cbm", precision = 12, scale = 4, nullable = false, updatable = false)
    private BigDecimal volumeCbm;

    @Column(name = "volumetric_weight_kg", precision = 12, scale = 3, nullable = false, updatable = false)
    private BigDecimal volumetricWeightKg;

    @Column(name = "chargeable_weight_kg", precision = 12, scale = 3, nullable = false, updatable = false)
    private BigDecimal chargeableWeightKg;

    @Column(name = "chargeable_units", precision = 12, scale = 4, nullable = false, updatable = false)
    private BigDecimal chargeableUnits;

    @Column(name = "tariff_currency", length = 3, nullable = false, updatable = false)
    private String tariffCurrency;

    @Column(name = "snapshot_rate_per_unit", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal snapshotRatePerUnit;

    @Column(name = "snapshot_minimum_charge", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal snapshotMinimumCharge;

    @Column(name = "snapshot_fuel_surcharge_pct", precision = 7, scale = 4, nullable = false, updatable = false)
    private BigDecimal snapshotFuelSurchargePct;

    @Column(name = "snapshot_fx_rate", precision = 19, scale = 8, nullable = false, updatable = false)
    private BigDecimal snapshotFxRate;

    @Column(name = "currency", length = 3, nullable = false, updatable = false)
    private String currency;

    @Column(name = "base_freight_charge", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal baseFreightCharge;

    @Column(name = "fuel_surcharge", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal fuelSurcharge;

    @Column(name = "origin_handling_charge", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal originHandlingCharge;

    @Column(name = "destination_handling_charge", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal destinationHandlingCharge;

    @Column(name = "import_clearance_charge", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal importClearanceCharge;

    @Column(name = "total_amount", precision = 19, scale = 4, nullable = false, updatable = false)
    private BigDecimal totalAmount;

    @Column(name = "indicative_currency", length = 3, updatable = false)
    private String indicativeCurrency;

    @Column(name = "indicative_total_amount", precision = 19, scale = 4, updatable = false)
    private BigDecimal indicativeTotalAmount;

    @Column(name = "indicative_rate", precision = 19, scale = 8, updatable = false)
    private BigDecimal indicativeRate;

    @Column(name = "indicative_rate_as_of", updatable = false)
    private Instant indicativeRateAsOf;

    @Column(name = "indicative_rate_source", length = 64, updatable = false)
    private String indicativeRateSource;

    @Column(name = "transit_days_min", nullable = false, updatable = false)
    private int transitDaysMin;

    @Column(name = "transit_days_max", nullable = false, updatable = false)
    private int transitDaysMax;

    @Column(name = "valid_until", nullable = false, updatable = false)
    private Instant validUntil;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** The organisation that asked for the quote (ADR 0018); null for an operator's. */
    @Column(name = "owner_organization_id", updatable = false)
    private UUID ownerOrganizationId;

    /** The estimate of duties and import VAT for the declared goods (ADR 0035); all null without goods. */
    @Column(name = "customs_market", length = 8, updatable = false)
    private String customsMarket;

    @Column(name = "customs_tariff_source_id", updatable = false)
    private UUID customsTariffSourceId;

    @Column(name = "customs_currency", length = 3, updatable = false)
    private String customsCurrency;

    @Column(name = "estimated_duties", precision = 19, scale = 4, updatable = false)
    private BigDecimal estimatedDuties;

    @Column(name = "estimated_import_vat", precision = 19, scale = 4, updatable = false)
    private BigDecimal estimatedImportVat;

    @Enumerated(EnumType.STRING)
    @Column(name = "duties_paid_by", length = 16, updatable = false)
    private PayerRole dutiesPaidBy;

    /** Whether the booking holds them with the freight: the quoting party pays them, in the freight currency. */
    @Column(name = "duties_collected", updatable = false)
    private Boolean dutiesCollected;

    protected FreightQuote() {
    }

    /** The customs estimate as the quote keeps it (ADR 0035); set before the quote is first saved. */
    public record CustomsSummary(String market, UUID tariffSourceId, String currency, BigDecimal duties,
                                 BigDecimal importVat, PayerRole paidBy, boolean collected) {
    }

    /** Without goods ({@code null}) the quote has no customs estimate. */
    public FreightQuote withCustoms(CustomsSummary customs) {
        if (customs == null) {
            return this;
        }
        this.customsMarket = Objects.requireNonNull(customs.market(), "market");
        this.customsTariffSourceId = Objects.requireNonNull(customs.tariffSourceId(), "tariffSourceId");
        this.customsCurrency = Objects.requireNonNull(customs.currency(), "currency");
        this.estimatedDuties = MoneyRounding.toStorageScale(customs.duties());
        this.estimatedImportVat = MoneyRounding.toStorageScale(customs.importVat());
        this.dutiesPaidBy = Objects.requireNonNull(customs.paidBy(), "paidBy");
        this.dutiesCollected = customs.collected();
        return this;
    }

    /** What a booking holds for customs on top of the freight: the estimate if collected, else zero. */
    public BigDecimal collectedCustomsEstimate() {
        return Boolean.TRUE.equals(dutiesCollected)
                ? estimatedDuties.add(estimatedImportVat)
                : MoneyRounding.toStorageScale(BigDecimal.ZERO);
    }

    public String getCustomsMarket() {
        return customsMarket;
    }

    public UUID getCustomsTariffSourceId() {
        return customsTariffSourceId;
    }

    public String getCustomsCurrency() {
        return customsCurrency;
    }

    public BigDecimal getEstimatedDuties() {
        return estimatedDuties;
    }

    public BigDecimal getEstimatedImportVat() {
        return estimatedImportVat;
    }

    public PayerRole getDutiesPaidBy() {
        return dutiesPaidBy;
    }

    public Boolean getDutiesCollected() {
        return dutiesCollected;
    }

    /** Sets the owner; must be called before the quote is saved. */
    public FreightQuote ownedBy(UUID organizationId) {
        this.ownerOrganizationId = organizationId;
        return this;
    }

    public UUID getOwnerOrganizationId() {
        return ownerOrganizationId;
    }

    public static FreightQuote create(
            String originUnlocode,
            String destinationUnlocode,
            TransportMode mode,
            Incoterm incoterm,
            PayerRole payerRole,
            BillingUnit billingUnit,
            int packageCount,
            BigDecimal actualWeightKg,
            BigDecimal volumeCbm,
            BigDecimal volumetricWeightKg,
            BigDecimal chargeableWeightKg,
            BigDecimal chargeableUnits,
            String tariffCurrency,
            BigDecimal snapshotRatePerUnit,
            BigDecimal snapshotMinimumCharge,
            BigDecimal snapshotFuelSurchargePct,
            BigDecimal snapshotFxRate,
            String currency,
            BigDecimal baseFreightCharge,
            BigDecimal fuelSurcharge,
            BigDecimal originHandlingCharge,
            BigDecimal destinationHandlingCharge,
            BigDecimal importClearanceCharge,
            BigDecimal totalAmount,
            int transitDaysMin,
            int transitDaysMax,
            Instant validUntil,
            Instant createdAt) {
        FreightQuote q = new FreightQuote();
        q.originUnlocode = Objects.requireNonNull(originUnlocode, "originUnlocode");
        q.destinationUnlocode = Objects.requireNonNull(destinationUnlocode, "destinationUnlocode");
        q.mode = Objects.requireNonNull(mode, "mode");
        q.incoterm = Objects.requireNonNull(incoterm, "incoterm");
        q.payerRole = Objects.requireNonNull(payerRole, "payerRole");
        q.billingUnit = Objects.requireNonNull(billingUnit, "billingUnit");
        q.packageCount = packageCount;
        q.actualWeightKg = Objects.requireNonNull(actualWeightKg, "actualWeightKg");
        q.volumeCbm = Objects.requireNonNull(volumeCbm, "volumeCbm");
        q.volumetricWeightKg = Objects.requireNonNull(volumetricWeightKg, "volumetricWeightKg");
        q.chargeableWeightKg = Objects.requireNonNull(chargeableWeightKg, "chargeableWeightKg");
        q.chargeableUnits = Objects.requireNonNull(chargeableUnits, "chargeableUnits");
        q.tariffCurrency = Objects.requireNonNull(tariffCurrency, "tariffCurrency");
        q.snapshotRatePerUnit = MoneyRounding.toStorageScale(snapshotRatePerUnit);
        q.snapshotMinimumCharge = MoneyRounding.toStorageScale(snapshotMinimumCharge);
        q.snapshotFuelSurchargePct = snapshotFuelSurchargePct;
        q.snapshotFxRate = snapshotFxRate;
        q.currency = Objects.requireNonNull(currency, "currency");
        q.baseFreightCharge = MoneyRounding.toStorageScale(baseFreightCharge);
        q.fuelSurcharge = MoneyRounding.toStorageScale(fuelSurcharge);
        q.originHandlingCharge = MoneyRounding.toStorageScale(originHandlingCharge);
        q.destinationHandlingCharge = MoneyRounding.toStorageScale(destinationHandlingCharge);
        q.importClearanceCharge = MoneyRounding.toStorageScale(importClearanceCharge);
        q.totalAmount = MoneyRounding.toStorageScale(totalAmount);
        q.transitDaysMin = transitDaysMin;
        q.transitDaysMax = transitDaysMax;
        q.validUntil = Objects.requireNonNull(validUntil, "validUntil");
        q.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        return q;
    }

    /**
     * Records the indicative total shown with the quote. {@code indicative} is null when the currency was asked
     * for but banking-service could not be reached.
     */
    public FreightQuote withIndicative(String currency, IndicativePricing.Indicative indicative) {
        this.indicativeCurrency = currency;
        if (currency != null && indicative != null) {
            this.indicativeTotalAmount = MoneyRounding.toStorageScale(indicative.totalAmount());
            this.indicativeRate = indicative.rate();
            this.indicativeRateAsOf = indicative.asOf();
            this.indicativeRateSource = indicative.source();
        }
        return this;
    }

    public String getIndicativeCurrency() {
        return indicativeCurrency;
    }

    public BigDecimal getIndicativeTotalAmount() {
        return indicativeTotalAmount;
    }

    public BigDecimal getIndicativeRate() {
        return indicativeRate;
    }

    public Instant getIndicativeRateAsOf() {
        return indicativeRateAsOf;
    }

    public String getIndicativeRateSource() {
        return indicativeRateSource;
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

    public Incoterm getIncoterm() {
        return incoterm;
    }

    public PayerRole getPayerRole() {
        return payerRole;
    }

    public BillingUnit getBillingUnit() {
        return billingUnit;
    }

    public int getPackageCount() {
        return packageCount;
    }

    public BigDecimal getActualWeightKg() {
        return actualWeightKg;
    }

    public BigDecimal getVolumeCbm() {
        return volumeCbm;
    }

    public BigDecimal getVolumetricWeightKg() {
        return volumetricWeightKg;
    }

    public BigDecimal getChargeableWeightKg() {
        return chargeableWeightKg;
    }

    public BigDecimal getChargeableUnits() {
        return chargeableUnits;
    }

    public String getTariffCurrency() {
        return tariffCurrency;
    }

    public BigDecimal getSnapshotRatePerUnit() {
        return snapshotRatePerUnit;
    }

    public BigDecimal getSnapshotMinimumCharge() {
        return snapshotMinimumCharge;
    }

    public BigDecimal getSnapshotFuelSurchargePct() {
        return snapshotFuelSurchargePct;
    }

    public BigDecimal getSnapshotFxRate() {
        return snapshotFxRate;
    }

    public String getCurrency() {
        return currency;
    }

    public BigDecimal getBaseFreightCharge() {
        return baseFreightCharge;
    }

    public BigDecimal getFuelSurcharge() {
        return fuelSurcharge;
    }

    public BigDecimal getOriginHandlingCharge() {
        return originHandlingCharge;
    }

    public BigDecimal getDestinationHandlingCharge() {
        return destinationHandlingCharge;
    }

    public BigDecimal getImportClearanceCharge() {
        return importClearanceCharge;
    }

    public BigDecimal getTotalAmount() {
        return totalAmount;
    }

    public int getTransitDaysMin() {
        return transitDaysMin;
    }

    public int getTransitDaysMax() {
        return transitDaysMax;
    }

    public Instant getValidUntil() {
        return validUntil;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
