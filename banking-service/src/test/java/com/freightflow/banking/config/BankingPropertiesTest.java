package com.freightflow.banking.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankingPropertiesTest {

    @Test
    void unknownLockingStrategyFailsStartup() {
        assertThatThrownBy(() -> new BankingProperties(List.of("NAD"), "optimistic", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("optimistic");
    }

    @Test
    void strategyIsNormalisedToLowerCase() {
        assertThat(new BankingProperties(List.of("NAD"), " Conditional-Update ", null, null, null, null).transferLockingStrategy())
                .isEqualTo(BankingProperties.STRATEGY_CONDITIONAL_UPDATE);
    }

    @Test
    void missingStrategyDefaultsToPessimistic() {
        assertThat(new BankingProperties(List.of("NAD"), null, null, null, null, null).transferLockingStrategy())
                .isEqualTo(BankingProperties.STRATEGY_PESSIMISTIC);
    }
}
