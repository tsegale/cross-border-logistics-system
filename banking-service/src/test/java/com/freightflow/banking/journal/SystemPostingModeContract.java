package com.freightflow.banking.journal;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.reconciliation.ReconciliationService;
import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse;
import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What every {@link SystemPostingMode} must guarantee (ADR 0010): concurrent postings against one system account
 * stay exact, the system balance is still readable, and reconciliation is clean. Each subclass runs it in one mode.
 */
abstract class SystemPostingModeContract extends IntegrationTest {

    static final UUID CLEARING_EUR = UUID.fromString("01930000-0000-7000-8000-000000000007");

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerService ledgerService;

    @Autowired
    ReconciliationService reconciliationService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    DataSource dataSource;

    abstract SystemPostingMode mode();

    @Test
    void concurrentDepositsAgainstOneClearingRowStayExact() throws Exception {
        BigDecimal clearingBefore = accountService.getAccount(CLEARING_EUR.toString()).balance();
        int deposits = 48;
        List<AccountResponse> customers = new ArrayList<>();
        for (int i = 0; i < deposits; i++) {
            customers.add(accountService.createCustomerAccount(new CreateAccountRequest("Hot " + mode() + " " + i, "EUR")));
        }

        CountDownLatch start = new CountDownLatch(1);
        List<Future<JournalEntryResponse>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(deposits)) {
            for (AccountResponse customer : customers) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return ledgerService.deposit(customer.id().toString(),
                            new DepositRequest(new BigDecimal("10.00"), "EUR", "HOT-" + mode()), "hot-" + UUID.randomUUID());
                }));
            }
            start.countDown();
            for (Future<JournalEntryResponse> f : futures) {
                f.get(60, TimeUnit.SECONDS);
            }
        }

        assertThat(accountService.getAccount(CLEARING_EUR.toString()).balance())
                .isEqualByComparingTo(clearingBefore.subtract(new BigDecimal("480.00")));
        for (AccountResponse customer : customers) {
            assertThat(accountService.getAccount(customer.id().toString()).balance()).isEqualByComparingTo("10.00");
        }
        ReconciliationReportResponse report = reconciliationService.reconcile();
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.balanced()).isTrue();
    }

    @Test
    void customerPostingsAlwaysCarryARunningBalance() {
        AccountResponse customer = accountService.createCustomerAccount(new CreateAccountRequest("Running " + mode(), "EUR"));
        JournalEntryResponse entry = ledgerService.deposit(customer.id().toString(),
                new DepositRequest(new BigDecimal("25.00"), "EUR", "RUN"), "run-" + UUID.randomUUID());

        assertThat(entry.postings()).filteredOn(p -> p.accountId().equals(customer.id()))
                .singleElement()
                .satisfies(p -> assertThat(p.runningBalance()).isEqualByComparingTo("25.00"));
    }

    /**
     * Holds the CLEARING-EUR row lock a writer takes ({@code FOR NO KEY UPDATE}, what Hibernate's PESSIMISTIC_WRITE
     * and a balance UPDATE take on PostgreSQL) on another connection while one deposit runs; returns the deposit's
     * duration. The posting's foreign key check takes {@code FOR KEY SHARE}, which does not conflict with it.
     */
    Duration depositWhileClearingRowIsLocked(Duration hold) throws Exception {
        AccountResponse customer = accountService.createCustomerAccount(new CreateAccountRequest("Blocked " + mode(), "EUR"));
        CountDownLatch locked = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            Future<?> holder = pool.submit(() -> {
                try (Connection connection = dataSource.getConnection()) {
                    connection.setAutoCommit(false);
                    try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE")) {
                        lock.setObject(1, CLEARING_EUR);
                        lock.executeQuery().close();
                    }
                    locked.countDown();
                    Thread.sleep(hold.toMillis());
                    connection.rollback();
                }
                return null;
            });
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();
            ledgerService.deposit(customer.id().toString(),
                    new DepositRequest(new BigDecimal("1.00"), "EUR", "BLOCKED"), "blocked-" + UUID.randomUUID());
            Duration took = Duration.ofNanos(System.nanoTime() - started);
            holder.get(30, TimeUnit.SECONDS);
            return took;
        }
    }
}
