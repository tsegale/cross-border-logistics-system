package com.freightflow.banking.journal;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** The Phase 1 behaviour, kept as the benchmark baseline: the system row is locked like a customer row. */
@TestPropertySource(properties = "freightflow.banking.system-accounts.posting-mode=locked")
class LockedSystemPostingIntegrationTest extends SystemPostingModeContract {

    @Override
    SystemPostingMode mode() {
        return SystemPostingMode.LOCKED;
    }

    @Test
    void aLockedClearingRowBlocksDepositsUntilItIsReleased() throws Exception {
        assertThat(depositWhileClearingRowIsLocked(Duration.ofSeconds(2))).isGreaterThan(Duration.ofMillis(1_800));
    }
}
