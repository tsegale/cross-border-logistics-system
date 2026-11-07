package com.freightflow.logistics.quote;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.common.UnsupportedCurrencyException;
import com.freightflow.logistics.quote.ChargeableWeightCalculator.CalculationResult;
import com.freightflow.logistics.quote.dto.PackageSpecDto;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChargeableWeightCalculatorTest {

    @Test
    void airFreightVolumetricWinsWhenLightAndBulkyPerIataResolution502() {
        // 2 cartons of 120cm x 100cm x 100cm = 2 * 1,200,000 cm³ = 2,400,000 cm³ (2.4000 CBM)
        // Volumetric weight = 2,400,000 / 6000 = 400.000 kg; actual weight = 2 * 85 kg = 170.000 kg
        CalculationResult res = ChargeableWeightCalculator.calculate(
                TransportMode.AIR,
                List.of(new PackageSpecDto(
                        new BigDecimal("120"),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("85.0"),
                        2,
                        "Bulky Namibian textiles"
                ))
        );

        assertThat(res.totalPieces()).isEqualTo(2);
        assertThat(res.actualWeightKg()).isEqualByComparingTo("170.000");
        assertThat(res.volumeCbm()).isEqualByComparingTo("2.4000");
        assertThat(res.volumetricWeightKg()).isEqualByComparingTo("400.000");
        assertThat(res.chargeableWeightKg()).isEqualByComparingTo("400.000");
        assertThat(res.billingUnit()).isEqualTo(BillingUnit.KG);
        assertThat(res.chargeableUnits()).isEqualByComparingTo("400.0000");
    }

    @Test
    void airFreightActualWeightWinsWhenDenseCargo() {
        // 1 crate of 50cm x 50cm x 50cm = 125,000 cm³ -> volumetric = 125,000 / 6000 = 20.833 kg
        // Actual weight = 120.000 kg -> chargeable = 120.000 kg
        CalculationResult res = ChargeableWeightCalculator.calculate(
                TransportMode.AIR,
                List.of(new PackageSpecDto(
                        new BigDecimal("50"),
                        new BigDecimal("50"),
                        new BigDecimal("50"),
                        new BigDecimal("120"),
                        1,
                        "Dense mining spare parts"
                ))
        );

        assertThat(res.actualWeightKg()).isEqualByComparingTo("120.000");
        assertThat(res.volumetricWeightKg()).isEqualByComparingTo("20.833");
        assertThat(res.chargeableWeightKg()).isEqualByComparingTo("120.000");
        assertThat(res.billingUnit()).isEqualTo(BillingUnit.KG);
    }

    @Test
    void seaLclWmRevenueTonUsesGreaterOfMetricTonsAndCbm() {
        // Measure-heavy LCL: 3 pallets of 120cm x 100cm x 150cm = 3 * 1.8 CBM = 5.4000 CBM; actual = 3 * 600 kg = 1,800 kg (1.8 t)
        CalculationResult measureHeavy = ChargeableWeightCalculator.calculate(
                TransportMode.SEA_LCL,
                List.of(new PackageSpecDto(
                        new BigDecimal("120"),
                        new BigDecimal("100"),
                        new BigDecimal("150"),
                        new BigDecimal("600"),
                        3,
                        "Palletized fishmeal"
                ))
        );
        assertThat(measureHeavy.actualWeightKg()).isEqualByComparingTo("1800.000");
        assertThat(measureHeavy.volumeCbm()).isEqualByComparingTo("5.4000");
        assertThat(measureHeavy.volumetricWeightKg()).isEqualByComparingTo("5400.000");
        assertThat(measureHeavy.chargeableWeightKg()).isEqualByComparingTo("5400.000");
        assertThat(measureHeavy.billingUnit()).isEqualTo(BillingUnit.WM_TON);
        assertThat(measureHeavy.chargeableUnits()).isEqualByComparingTo("5.4000");

        // Weight-heavy LCL: 2 crates of 100cm x 100cm x 100cm = 2.0000 CBM; actual = 2 * 1,400 kg = 2,800 kg (2.8 t)
        CalculationResult weightHeavy = ChargeableWeightCalculator.calculate(
                TransportMode.SEA_LCL,
                List.of(new PackageSpecDto(
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("100"),
                        new BigDecimal("1400"),
                        2,
                        "Copper cathodes"
                ))
        );
        assertThat(weightHeavy.actualWeightKg()).isEqualByComparingTo("2800.000");
        assertThat(weightHeavy.volumeCbm()).isEqualByComparingTo("2.0000");
        assertThat(weightHeavy.chargeableWeightKg()).isEqualByComparingTo("2800.000");
        assertThat(weightHeavy.chargeableUnits()).isEqualByComparingTo("2.8000");
    }

    @Test
    void rejectsEmptyOrNonPositivePackagesAndChecksMoneyRounding() {
        assertThatThrownBy(() -> ChargeableWeightCalculator.calculate(TransportMode.AIR, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChargeableWeightCalculator.calculate(
                TransportMode.AIR,
                List.of(new PackageSpecDto(BigDecimal.ZERO, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ONE, 1, null))
        )).isInstanceOf(IllegalArgumentException.class);

        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("10.125"), "EUR"))
                .isEqualByComparingTo("10.12");
        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("10.135"), "EUR"))
                .isEqualByComparingTo("10.14");
        assertThat(MoneyRounding.hasExcessMinorPrecision(new BigDecimal("12.345"), "NAD")).isTrue();
        assertThat(MoneyRounding.hasExcessMinorPrecision(new BigDecimal("12.34"), "NAD")).isFalse();
        assertThatThrownBy(() -> MoneyRounding.validateSupportedCurrency("USD", Set.of("NAD", "ZAR", "EUR")))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }
}
