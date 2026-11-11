package com.freightflow.banking.journal;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestPropertySource(properties = "freightflow.banking.system-accounts.posting-mode=unlocked")
class UnlockedSystemPostingIntegrationTest extends SystemPostingModeContract {

    @Override
    SystemPostingMode mode() {
        return SystemPostingMode.UNLOCKED;
    }

    @Test
    void systemPostingHasNoRunningBalanceAndNoShard() {
        AccountResponse customer = accountService.createCustomerAccount(TestCallers.OPERATOR, new CreateAccountRequest("EUR", TestCallers.NAMIB));
        JournalEntryResponse entry = ledgerService.deposit(TestCallers.OPERATOR, customer.id().toString(),
                new DepositRequest(new BigDecimal("5.00"), "EUR", "U"), "u-" + UUID.randomUUID());

        assertThat(entry.postings()).filteredOn(p -> p.accountId().equals(CLEARING_EUR))
                .singleElement()
                .satisfies(p -> assertThat(p.runningBalance()).isNull());
    }

    @Test
    void aLockedClearingRowNoLongerBlocksDeposits() throws Exception {
        assertThat(depositWhileClearingRowIsLocked(Duration.ofSeconds(2))).isLessThan(Duration.ofSeconds(1));
    }

    @Test
    void theDatabaseRejectsABalanceShardForACustomerAccount() {
        AccountResponse customer = accountService.createCustomerAccount(TestCallers.OPERATOR, new CreateAccountRequest("EUR", TestCallers.NAMIB));
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO account_balance_shards (account_id, shard, balance, updated_at) VALUES (?, 0, 0, now())
                """, customer.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
