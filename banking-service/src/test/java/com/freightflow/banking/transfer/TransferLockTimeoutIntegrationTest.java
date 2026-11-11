package com.freightflow.banking.transfer;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The idempotency lock timeout must cap only the advisory lock. Before the fix, {@code SET LOCAL lock_timeout}
 * stayed in force for the whole transaction, so the account lock that follows also failed after 5 s with an
 * unmapped SQLSTATE 55P03 (a 500).
 */
@TestPropertySource(properties = {
        "freightflow.banking.idempotency.lock-timeout=300ms",
        "freightflow.banking.ledger.account-lock-timeout=2s"
})
class TransferLockTimeoutIntegrationTest extends IntegrationTest {

    private static final Duration IDEMPOTENCY_LOCK_TIMEOUT = Duration.ofMillis(300);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DataSource dataSource;

    @Test
    void transferWaitsForAnAccountLockHeldLongerThanTheIdempotencyLockTimeout() throws Exception {
        AccountResponse source = fundedAccount();
        AccountResponse target = accountService.createCustomerAccount(new CreateAccountRequest("Lock Target", "NAD"));
        Duration hold = IDEMPOTENCY_LOCK_TIMEOUT.multipliedBy(4);

        long started = System.nanoTime();
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = holdRowLock(executor, source.id(), hold);
            MvcResult result = mockMvc.perform(transfer(source, target, "lock-wait-" + UUID.randomUUID()))
                    .andExpect(status().isCreated())
                    .andReturn();
            holder.get(10, TimeUnit.SECONDS);
            assertThat(result.getResponse().getContentAsString()).contains("\"sourceAmount\":10.00");
        }
        long waitedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(waitedMillis).isGreaterThanOrEqualTo(hold.toMillis() - 50);
        assertThat(accountService.getAccount(source.id().toString()).ledgerBalance()).isEqualByComparingTo("90.00");
    }

    @Test
    void lockHeldPastTheAccountLockTimeoutIsARetryable503AndTheRetrySucceeds() throws Exception {
        AccountResponse source = fundedAccount();
        AccountResponse target = accountService.createCustomerAccount(new CreateAccountRequest("Lock Target", "NAD"));
        String key = "lock-timeout-" + UUID.randomUUID();

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = holdRowLock(executor, source.id(), Duration.ofSeconds(4));
            mockMvc.perform(transfer(source, target, key))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/lock-timeout"));
            holder.get(10, TimeUnit.SECONDS);
        }

        mockMvc.perform(transfer(source, target, key)).andExpect(status().isCreated());
        assertThat(accountService.getAccount(source.id().toString()).ledgerBalance()).isEqualByComparingTo("90.00");
    }

    private AccountResponse fundedAccount() {
        AccountResponse account = accountService.createCustomerAccount(new CreateAccountRequest("Lock Source", "NAD"));
        ledgerService.deposit(account.id().toString(),
                new DepositRequest(new BigDecimal("100.00"), "NAD", "seed"), "seed-" + UUID.randomUUID());
        return account;
    }

    private static org.springframework.test.web.servlet.RequestBuilder transfer(
            AccountResponse source, AccountResponse target, String key) {
        return post("/api/v1/transfers")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"sourceAccount":"%s","targetAccount":"%s","amount":10.00,"currency":"NAD","reference":"LOCK"}
                        """.formatted(source.accountNumber(), target.accountNumber()));
    }

    /** Locks one account row on its own connection and returns once the lock is held; releases it after {@code hold}. */
    private Future<?> holdRowLock(ExecutorService executor, UUID accountId, Duration hold) throws InterruptedException {
        CountDownLatch locked = new CountDownLatch(1);
        Future<?> future = executor.submit(() -> {
            try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement lock = connection.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
                    lock.setObject(1, accountId);
                    lock.executeQuery().close();
                }
                locked.countDown();
                Thread.sleep(hold.toMillis());
                connection.rollback();
            }
            return null;
        });
        assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
        return future;
    }
}
