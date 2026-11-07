package com.freightflow.banking.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyRoundingTest {

    @Test
    void halfEvenRoundingEdgeCasesAtMidpoint() {
        // 10.125 -> 10.12 (2 is even)
        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("10.125"), "NAD"))
                .isEqualByComparingTo("10.12")
                .hasScaleOf(2);

        // 10.135 -> 10.14 (4 is even)
        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("10.135"), "ZAR"))
                .isEqualByComparingTo("10.14")
                .hasScaleOf(2);

        // 10.1351 -> 10.14 (above midpoint)
        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("10.1351"), "EUR"))
                .isEqualByComparingTo("10.14");

        // Negative midpoint: -0.005 -> 0.00, -0.015 -> -0.02
        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("-0.005"), "EUR"))
                .isEqualByComparingTo("0.00");
        assertThat(MoneyRounding.roundToMinorUnits(new BigDecimal("-0.015"), "EUR"))
                .isEqualByComparingTo("-0.02");
    }

    @Test
    void normalizesCustomerAmountToMinorUnitsAndStorageScaleFour() {
        BigDecimal normalized = MoneyRounding.normalizeCustomerAmount(new BigDecimal("250.125"), "NAD");
        assertThat(normalized).isEqualTo(new BigDecimal("250.1200"));
        assertThat(MoneyRounding.toStorageScale(new BigDecimal("42.5"))).isEqualTo(new BigDecimal("42.5000"));
    }

    @Test
    void detectsExcessMinorPrecision() {
        assertThat(MoneyRounding.hasExcessMinorPrecision(new BigDecimal("100.50"), "NAD")).isFalse();
        assertThat(MoneyRounding.hasExcessMinorPrecision(new BigDecimal("100.5000"), "NAD")).isFalse();
        assertThat(MoneyRounding.hasExcessMinorPrecision(new BigDecimal("100.501"), "NAD")).isTrue();
    }

    @Test
    void validatesSupportedCurrenciesAndRejectsOthers() {
        Set<String> supported = Set.of("NAD", "ZAR", "EUR");
        assertThat(MoneyRounding.validateSupportedCurrency("nad", supported).getCurrencyCode()).isEqualTo("NAD");

        assertThatThrownBy(() -> MoneyRounding.validateSupportedCurrency("USD", supported))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .satisfies(ex -> assertThat(((UnsupportedCurrencyException) ex).getSupportedCurrencies())
                        .containsExactly("EUR", "NAD", "ZAR"));

        assertThatThrownBy(() -> MoneyRounding.validateSupportedCurrency(" ", supported))
                .isInstanceOf(UnsupportedCurrencyException.class);
    }
}
