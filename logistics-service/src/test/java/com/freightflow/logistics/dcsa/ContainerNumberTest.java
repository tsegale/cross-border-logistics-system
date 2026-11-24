package com.freightflow.logistics.dcsa;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ContainerNumberTest {

    /** CSQU 305438 3 is ISO 6346's own worked example. */
    @ParameterizedTest
    @ValueSource(strings = {"CSQU3054383", "csqu 305438-3", "MSKU9070323", "HLXU1234561"})
    void validNumbersPassNormalised(String number) {
        assertThat(ContainerNumber.isValid(number)).isTrue();
        assertThat(ContainerNumber.validate(number)).matches("^[A-Z]{4}[0-9]{7}$");
    }

    @Test
    void aWrongCheckDigitIsRefusedWithTheRightOne() {
        assertThatThrownBy(() -> ContainerNumber.validate("CSQU3054384"))
                .hasMessageContaining("expected 3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CSQX3054383", "CSQU305438", "CS1U3054383", "CSQU30543831", ""})
    void malformedNumbersAreRefused(String number) {
        assertThat(ContainerNumber.isValid(number)).isFalse();
    }

    @Test
    void lettersSkipTheMultiplesOfEleven() {
        // A = 10, K = 21 (11 skipped), L = 23 (22 skipped), U = 32, V = 34 (33 skipped), Z = 38.
        assertThat(ContainerNumber.checkDigit("AAAU000000")).isEqualTo((10 + 20 + 40 + 32 * 8) % 11 % 10);
        assertThat(ContainerNumber.checkDigit("ZZZU000000")).isEqualTo((38 + 76 + 152 + 32 * 8) % 11 % 10);
    }
}
