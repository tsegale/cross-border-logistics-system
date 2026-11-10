package com.freightflow.logistics.quote;

import com.freightflow.logistics.common.MoneyRounding;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

/**
 * Prices the billable legs of a quote from a tariff (ADR 0008). Pure: no database, no clock, so every rule has a
 * unit test. Amounts are in the tariff currency and rounded HALF_EVEN to its minor units.
 */
public final class FreightPricing {

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    private FreightPricing() {
    }

    /** The tariff values pricing needs, in the tariff currency. */
    public record TariffRates(
            String currency,
            BigDecimal ratePerUnit,
            BigDecimal minimumCharge,
            BigDecimal fuelSurchargePercent,
            BigDecimal originHandlingFee,
            BigDecimal destinationHandlingFee,
            BigDecimal importClearanceFee
    ) {
        public TariffRates {
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(ratePerUnit, "ratePerUnit");
            Objects.requireNonNull(minimumCharge, "minimumCharge");
            Objects.requireNonNull(fuelSurchargePercent, "fuelSurchargePercent");
            Objects.requireNonNull(originHandlingFee, "originHandlingFee");
            Objects.requireNonNull(destinationHandlingFee, "destinationHandlingFee");
            Objects.requireNonNull(importClearanceFee, "importClearanceFee");
        }

        public static TariffRates of(Tariff tariff) {
            return new TariffRates(tariff.getCurrency(), tariff.getRatePerUnit(), tariff.getMinimumCharge(),
                    tariff.getFuelSurchargePercent(), tariff.getOriginHandlingFee(),
                    tariff.getDestinationHandlingFee(), tariff.getImportClearanceFee());
        }
    }

    public record Charges(
            BigDecimal baseFreight,
            BigDecimal fuelSurcharge,
            BigDecimal originHandling,
            BigDecimal destinationHandling,
            BigDecimal importClearance
    ) {
        public BigDecimal total() {
            return baseFreight.add(fuelSurcharge).add(originHandling).add(destinationHandling).add(importClearance);
        }
    }

    /**
     * Main carriage is {@code max(chargeableUnits x ratePerUnit, minimumCharge)}, rounded; the fuel surcharge is a
     * percentage of that rounded freight. A leg the payer does not pay is zero.
     */
    public static Charges price(TariffRates rates, BigDecimal chargeableUnits, Set<ChargeLeg> legs) {
        String ccy = rates.currency();
        BigDecimal zero = MoneyRounding.toStorageScale(BigDecimal.ZERO);
        BigDecimal baseFreight = zero;
        BigDecimal fuel = zero;
        if (legs.contains(ChargeLeg.MAIN_CARRIAGE)) {
            baseFreight = MoneyRounding.normalizeCustomerAmount(
                    chargeableUnits.multiply(rates.ratePerUnit()).max(rates.minimumCharge()), ccy);
            fuel = MoneyRounding.normalizeCustomerAmount(baseFreight.multiply(rates.fuelSurchargePercent())
                    .divide(ONE_HUNDRED, MoneyRounding.STORAGE_SCALE + 4, MoneyRounding.ROUNDING_MODE), ccy);
        }
        return new Charges(
                baseFreight,
                fuel,
                legs.contains(ChargeLeg.ORIGIN_HANDLING) ? MoneyRounding.normalizeCustomerAmount(rates.originHandlingFee(), ccy) : zero,
                legs.contains(ChargeLeg.DESTINATION_HANDLING) ? MoneyRounding.normalizeCustomerAmount(rates.destinationHandlingFee(), ccy) : zero,
                legs.contains(ChargeLeg.IMPORT_CLEARANCE) ? MoneyRounding.normalizeCustomerAmount(rates.importClearanceFee(), ccy) : zero);
    }
}
