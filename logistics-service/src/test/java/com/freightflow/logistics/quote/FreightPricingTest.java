package com.freightflow.logistics.quote;

import com.freightflow.logistics.quote.FreightPricing.Charges;
import com.freightflow.logistics.quote.FreightPricing.TariffRates;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Tariff arithmetic, using the seeded NAWVB -> DEHAM SEA_LCL tariff (EUR). */
class FreightPricingTest {

    private static final TariffRates SEA_LCL = new TariffRates("EUR", new BigDecimal("95.0000"),
            new BigDecimal("180.0000"), new BigDecimal("12.5000"), new BigDecimal("65.0000"),
            new BigDecimal("85.0000"), new BigDecimal("55.0000"));
    private static final Set<ChargeLeg> ALL = EnumSet.allOf(ChargeLeg.class);

    @Test
    void freightIsChargeableUnitsTimesRate() {
        Charges charges = FreightPricing.price(SEA_LCL, new BigDecimal("3.6000"), Set.of(ChargeLeg.MAIN_CARRIAGE));

        assertThat(charges.baseFreight()).isEqualByComparingTo("342.00");
        assertThat(charges.fuelSurcharge()).isEqualByComparingTo("42.75");
        assertThat(charges.total()).isEqualByComparingTo("384.75");
    }

    @Test
    void minimumChargeAppliesWhenTheRatedFreightIsBelowIt() {
        // 1.2 W/M x 95 = 114.00 < minimum 180.00
        Charges charges = FreightPricing.price(SEA_LCL, new BigDecimal("1.2000"), Set.of(ChargeLeg.MAIN_CARRIAGE));

        assertThat(charges.baseFreight()).isEqualByComparingTo("180.00");
        assertThat(charges.fuelSurcharge()).isEqualByComparingTo("22.50");
    }

    @Test
    void minimumChargeIsTheFloorNotAnAddOnAtExactlyTheThreshold() {
        // 180 / 95 = 1.8947...: 1.8948 x 95 = 180.006 rounds to 180.01, just above the minimum
        Charges charges = FreightPricing.price(SEA_LCL, new BigDecimal("1.8948"), Set.of(ChargeLeg.MAIN_CARRIAGE));

        assertThat(charges.baseFreight()).isEqualByComparingTo("180.01");
    }

    @Test
    void fuelSurchargeIsRoundedHalfEven() {
        // 3.3 x 95 = 313.50; 12.5% = 39.1875 -> 39.19
        Charges charges = FreightPricing.price(SEA_LCL, new BigDecimal("3.3000"), Set.of(ChargeLeg.MAIN_CARRIAGE));

        assertThat(charges.fuelSurcharge()).isEqualByComparingTo("39.19");
    }

    @Test
    void legsThePayerDoesNotPayAreZero() {
        Charges charges = FreightPricing.price(SEA_LCL, new BigDecimal("3.6000"), Set.of(ChargeLeg.ORIGIN_HANDLING));

        assertThat(charges.baseFreight()).isZero();
        assertThat(charges.fuelSurcharge()).isZero();
        assertThat(charges.destinationHandling()).isZero();
        assertThat(charges.total()).isEqualByComparingTo("65.00");
    }

    @Test
    void everyLegSumsToTheTotal() {
        Charges charges = FreightPricing.price(SEA_LCL, new BigDecimal("3.6000"), ALL);

        assertThat(charges.total()).isEqualByComparingTo("589.75");
    }
}
