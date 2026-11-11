package com.freightflow.banking.journal;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.dto.DepositRequest;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@TestPropertySource(properties = "freightflow.banking.system-accounts.posting-mode=sharded")
class ShardedSystemPostingIntegrationTest extends SystemPostingModeContract {

    @Override
    SystemPostingMode mode() {
        return SystemPostingMode.SHARDED;
    }

    @Test
    void systemPostingRecordsItsShardAndTheShardsRunningBalance() {
        AccountResponse customer = accountService.createCustomerAccount(TestCallers.OPERATOR, new CreateAccountRequest("EUR", TestCallers.NAMIB));
        var entry = ledgerService.deposit(TestCallers.OPERATOR, customer.id().toString(),
                new DepositRequest(new BigDecimal("5.00"), "EUR", "S"), "s-" + UUID.randomUUID());

        Integer shard = jdbcTemplate.queryForObject(
                "SELECT shard FROM postings WHERE journal_entry_id = ? AND account_id = ?",
                Integer.class, entry.id(), CLEARING_EUR);
        BigDecimal running = jdbcTemplate.queryForObject(
                "SELECT running_balance FROM postings WHERE journal_entry_id = ? AND account_id = ?",
                BigDecimal.class, entry.id(), CLEARING_EUR);
        BigDecimal shardBalance = jdbcTemplate.queryForObject(
                "SELECT balance FROM account_balance_shards WHERE account_id = ? AND shard = ?",
                BigDecimal.class, CLEARING_EUR, shard);
        assertThat(shard).isBetween(0, 15);
        assertThat(running).isEqualByComparingTo(shardBalance);
    }

    @Test
    void aLockedClearingRowNoLongerBlocksDeposits() throws Exception {
        assertThat(depositWhileClearingRowIsLocked(Duration.ofSeconds(2))).isLessThan(Duration.ofSeconds(1));
    }
}
