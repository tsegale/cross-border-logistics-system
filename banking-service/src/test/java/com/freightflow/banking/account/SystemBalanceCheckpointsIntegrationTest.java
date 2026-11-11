package com.freightflow.banking.account;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.reconciliation.ReconciliationService;
import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checkpointed balances of unlocked system accounts (ADR 0010): exact, append-only and safe against late commits. */
class SystemBalanceCheckpointsIntegrationTest extends IntegrationTest {

    private static final UUID CLEARING_EUR = UUID.fromString("01930000-0000-7000-8000-000000000007");

    @Autowired
    private SystemBalanceCheckpoints checkpoints;

    @Autowired
    private SystemAccountBalances balances;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theBalanceIsTheSameBeforeAndAfterARollUpAndKeepsCountingAfterIt() {
        AccountResponse customer = customer();
        deposit(customer, "10.00");
        BigDecimal before = balances.current(CLEARING_EUR);
        assertThat(before).isEqualByComparingTo(sumOfAllPostings());

        assertThat(checkpoints.rollUp(CLEARING_EUR)).isTrue();
        assertThat(balances.current(CLEARING_EUR)).isEqualByComparingTo(before);

        deposit(customer, "2.50");
        assertThat(balances.current(CLEARING_EUR)).isEqualByComparingTo(before.subtract(new BigDecimal("2.50")))
                .isEqualByComparingTo(sumOfAllPostings());
        assertThat(checkpoints.rollUp(CLEARING_EUR)).isTrue();
        assertThat(balances.current(CLEARING_EUR)).isEqualByComparingTo(sumOfAllPostings());
        assertClean();
    }

    @Test
    void nothingNewMeansNoNewCheckpoint() {
        deposit(customer(), "1.00");
        checkpoints.rollUp(CLEARING_EUR);
        int rows = checkpointRows();

        assertThat(checkpoints.rollUp(CLEARING_EUR)).isFalse();
        assertThat(checkpointRows()).isEqualTo(rows);
    }

    @Test
    void aPostingCommittedAfterTheCheckpointWasTakenIsStillCounted() throws Exception {
        AccountResponse customer = customer();
        deposit(customer, "100.00");
        CountDownLatch posted = new CountDownLatch(1);
        CountDownLatch checkpointTaken = new CountDownLatch(1);

        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            // A deposit whose transaction is open while the roll-up runs, and commits only after it.
            Future<?> slowDeposit = pool.submit(() -> transactions.executeWithoutResult(status -> {
                ledgerService.deposit(TestCallers.OPERATOR, customer.id().toString(),
                        new DepositRequest(new BigDecimal("7.00"), "EUR", "LATE"), "late-" + UUID.randomUUID());
                posted.countDown();
                await(checkpointTaken);
            }));
            assertThat(posted.await(30, TimeUnit.SECONDS)).isTrue();
            checkpoints.rollUp(CLEARING_EUR);
            checkpointTaken.countDown();
            slowDeposit.get(30, TimeUnit.SECONDS);
        }

        assertThat(balances.current(CLEARING_EUR)).isEqualByComparingTo(sumOfAllPostings());
        assertThat(checkpoints.rollUp(CLEARING_EUR)).as("the late posting is in the next checkpoint").isTrue();
        assertThat(balances.current(CLEARING_EUR)).isEqualByComparingTo(sumOfAllPostings());
        assertClean();
    }

    @Test
    void checkpointsAreAppendOnlyAndOnlyForSystemAccounts() {
        deposit(customer(), "1.00");
        checkpoints.rollUp(CLEARING_EUR);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE system_balance_checkpoints SET balance = 0 WHERE account_id = ?", CLEARING_EUR))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM system_balance_checkpoints WHERE account_id = ?", CLEARING_EUR))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO system_balance_checkpoints (id, account_id, covered_below, balance, postings_covered, created_at)
                VALUES (gen_random_uuid(), ?, '1'::xid8, 0, 0, now())
                """, customer().id()))
                .hasMessageContaining("customer account");
    }

    @Test
    void aTamperedCheckpointIsADiscrepancy() {
        deposit(customer(), "1.00");
        checkpoints.rollUp(CLEARING_EUR);
        // A forged checkpoint above the real one, as an attacker with database access might write.
        jdbcTemplate.update("""
                INSERT INTO system_balance_checkpoints (id, account_id, covered_below, balance, postings_covered, created_at)
                SELECT gen_random_uuid(), account_id, pg_snapshot_xmin(pg_current_snapshot()), balance + 1,
                       postings_covered, now()
                FROM system_balance_checkpoints WHERE account_id = ? ORDER BY covered_below DESC LIMIT 1
                """, CLEARING_EUR);

        var report = reconciliationService.reconcile();
        assertThat(report.balanced()).isFalse();
        assertThat(report.discrepancies()).anyMatch(d -> d.accountNumber().endsWith("#checkpoint"));
        // Leave the shared database clean for the next test: a genuine checkpoint above the forged one.
        deposit(customer(), "1.00");
        jdbcTemplate.update("""
                INSERT INTO system_balance_checkpoints (id, account_id, covered_below, balance, postings_covered, created_at)
                SELECT gen_random_uuid(), ?, pg_snapshot_xmin(pg_current_snapshot()),
                       (SELECT SUM(amount) FROM postings WHERE account_id = ? AND running_balance IS NULL), 0, now()
                """, CLEARING_EUR, CLEARING_EUR);
        assertClean();
    }

    private AccountResponse customer() {
        return accountService.createCustomerAccount(TestCallers.OPERATOR, new CreateAccountRequest("EUR", TestCallers.NAMIB));
    }

    private void deposit(AccountResponse account, String amount) {
        ledgerService.deposit(TestCallers.OPERATOR, account.id().toString(), new DepositRequest(new BigDecimal(amount), "EUR", "SEED"),
                "checkpoint-" + UUID.randomUUID());
    }

    private BigDecimal sumOfAllPostings() {
        BigDecimal locked = jdbcTemplate.queryForObject("SELECT balance FROM accounts WHERE id = ?", BigDecimal.class,
                CLEARING_EUR);
        BigDecimal shards = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM account_balance_shards WHERE account_id = ?", BigDecimal.class,
                CLEARING_EUR);
        BigDecimal unlocked = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE account_id = ? AND running_balance IS NULL",
                BigDecimal.class, CLEARING_EUR);
        return locked.add(shards).add(unlocked);
    }

    private int checkpointRows() {
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM system_balance_checkpoints WHERE account_id = ?", Integer.class, CLEARING_EUR);
        return rows == null ? 0 : rows;
    }

    private void assertClean() {
        var report = reconciliationService.reconcile();
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.balanced()).isTrue();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("checkpoint was not taken in time");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
