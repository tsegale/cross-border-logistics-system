package com.freightflow.banking.transfer;

import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.InsufficientFundsException;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.FxQuoteService;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.journal.JournalEntryRepository;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.PostingRepository;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.reconciliation.ReconciliationService;
import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.transfer.dto.CreateTransferRequest;
import com.freightflow.banking.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class TransferConcurrencyIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JournalEntryRepository journalEntryRepository;

    @Autowired
    private PostingRepository postingRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private FxQuoteService fxQuoteService;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private BankingProperties properties;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Test
    void twoHundredConcurrentTransfersDrainingOneAccountNeverOverdraw() throws Exception {
        AccountResponse source = accountService.createCustomerAccount(
                new CreateAccountRequest("Hot Source NAD", "NAD"));
        AccountResponse target = accountService.createCustomerAccount(
                new CreateAccountRequest("Hot Sink NAD", "NAD"));

        // Fund source with 5,000.00 NAD (enough for exactly 100 transfers of 50.00 NAD)
        ledgerService.deposit(
                source.id().toString(),
                new DepositRequest(new BigDecimal("5000.00"), "NAD", "Hot account seed"),
                "hot-seed-" + UUID.randomUUID()
        );

        int totalRequests = 200;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger createdCount = new AtomicInteger();
        AtomicInteger conflictCount = new AtomicInteger();
        AtomicInteger otherCount = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(totalRequests)) {
            List<Callable<Void>> tasks = new ArrayList<>(totalRequests);
            for (int i = 0; i < totalRequests; i++) {
                final int idx = i;
                tasks.add(() -> {
                    startGate.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/transfers")
                                    .header("Idempotency-Key", "drain-" + source.id() + "-" + idx)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "sourceAccount": "%s",
                                              "targetAccount": "%s",
                                              "amount": 50.00,
                                              "currency": "NAD",
                                              "reference": "DRAIN-%d"
                                            }
                                            """.formatted(source.accountNumber(), target.accountNumber(), idx)))
                            .andReturn();
                    int status = res.getResponse().getStatus();
                    if (status == 201) {
                        createdCount.incrementAndGet();
                    } else if (status == 409) {
                        conflictCount.incrementAndGet();
                    } else {
                        otherCount.incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(otherCount.get()).isZero();
        assertThat(createdCount.get()).isEqualTo(100);
        assertThat(conflictCount.get()).isEqualTo(100);

        assertThat(accountService.getAccount(source.id().toString()).balance())
                .isEqualByComparingTo("0.00");
        assertThat(accountService.getAccount(target.id().toString()).balance())
                .isEqualByComparingTo("5000.00");

        BigDecimal minRunningBalance = jdbcTemplate.queryForObject(
                "SELECT MIN(running_balance) FROM postings WHERE account_id = ?",
                BigDecimal.class,
                source.id()
        );
        assertThat(minRunningBalance).isGreaterThanOrEqualTo(BigDecimal.ZERO);

        ReconciliationReportResponse recon = reconciliationService.reconcile();
        assertThat(recon.balanced()).isTrue();
        assertThat(recon.discrepancies()).isEmpty();
    }

    @Test
    void hundredConcurrentBidirectionalPairsCompleteWithoutDeadlock() throws Exception {
        AccountResponse accountA = accountService.createCustomerAccount(
                new CreateAccountRequest("Bidirectional A", "NAD"));
        AccountResponse accountB = accountService.createCustomerAccount(
                new CreateAccountRequest("Bidirectional B", "NAD"));

        ledgerService.deposit(
                accountA.id().toString(),
                new DepositRequest(new BigDecimal("10000.00"), "NAD", "Seed A"),
                "seed-a-" + UUID.randomUUID()
        );
        ledgerService.deposit(
                accountB.id().toString(),
                new DepositRequest(new BigDecimal("10000.00"), "NAD", "Seed B"),
                "seed-b-" + UUID.randomUUID()
        );

        int pairs = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(pairs * 2)) {
            List<Callable<Void>> tasks = new ArrayList<>(pairs * 2);
            for (int i = 0; i < pairs; i++) {
                final int idx = i;
                tasks.add(() -> {
                    startGate.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/transfers")
                                    .header("Idempotency-Key", "ab-" + accountA.id() + "-" + idx)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "sourceAccount": "%s",
                                              "targetAccount": "%s",
                                              "amount": 25.00,
                                              "currency": "NAD",
                                              "reference": "A-TO-B-%d"
                                            }
                                            """.formatted(accountA.accountNumber(), accountB.accountNumber(), idx)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    }
                    return null;
                });
                tasks.add(() -> {
                    startGate.await();
                    MvcResult res = mockMvc.perform(post("/api/v1/transfers")
                                    .header("Idempotency-Key", "ba-" + accountB.id() + "-" + idx)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("""
                                            {
                                              "sourceAccount": "%s",
                                              "targetAccount": "%s",
                                              "amount": 15.00,
                                              "currency": "NAD",
                                              "reference": "B-TO-A-%d"
                                            }
                                            """.formatted(accountB.accountNumber(), accountA.accountNumber(), idx)))
                            .andReturn();
                    if (res.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    }
                    return null;
                });
            }
            List<Future<Void>> futures = tasks.stream().map(executor::submit).toList();
            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(successCount.get()).isEqualTo(pairs * 2);
        // A: 10000 - 100*25 + 100*15 = 9000.00; B: 10000 + 100*25 - 100*15 = 11000.00
        assertThat(accountService.getAccount(accountA.id().toString()).balance())
                .isEqualByComparingTo("9000.00");
        assertThat(accountService.getAccount(accountB.id().toString()).balance())
                .isEqualByComparingTo("11000.00");

        ReconciliationReportResponse recon = reconciliationService.reconcile();
        assertThat(recon.balanced()).isTrue();
        assertThat(recon.discrepancies()).isEmpty();
    }

    @Test
    void sameIdempotencyKeySentTwentyTimesConcurrentlyCreatesSingleJournalEntry() throws Exception {
        AccountResponse source = accountService.createCustomerAccount(
                new CreateAccountRequest("Idemp Concurrency Source", "NAD"));
        AccountResponse target = accountService.createCustomerAccount(
                new CreateAccountRequest("Idemp Concurrency Target", "NAD"));

        ledgerService.deposit(
                source.id().toString(),
                new DepositRequest(new BigDecimal("1000.00"), "NAD", "Seed idemp"),
                "seed-idemp-" + UUID.randomUUID()
        );

        String sharedKey = "concurrent-idemp-" + UUID.randomUUID();
        String body = """
                {
                  "sourceAccount": "%s",
                  "targetAccount": "%s",
                  "amount": 250.00,
                  "currency": "NAD",
                  "reference": "CONCURRENT-IDEMP-001"
                }
                """.formatted(source.accountNumber(), target.accountNumber());

        int callers = 20;
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>(callers);

        try (ExecutorService executor = Executors.newFixedThreadPool(callers)) {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return mockMvc.perform(post("/api/v1/transfers")
                                    .header("Idempotency-Key", sharedKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();
                }));
            }
            startGate.countDown();
            List<JsonNode> responses = new ArrayList<>(callers);
            for (Future<MvcResult> future : futures) {
                MvcResult res = future.get();
                assertThat(res.getResponse().getStatus()).isEqualTo(201);
                responses.add(JSON.readTree(res.getResponse().getContentAsString()));
            }

            Set<String> transferIds = responses.stream()
                    .map(r -> r.get("id").asString())
                    .collect(Collectors.toSet());
            Set<String> journalIds = responses.stream()
                    .map(r -> r.get("journalEntryId").asString())
                    .collect(Collectors.toSet());

            assertThat(transferIds).hasSize(1);
            assertThat(journalIds).hasSize(1);
        }

        // Balance debited exactly once (1000 - 250 = 750)
        assertThat(accountService.getAccount(source.id().toString()).balance())
                .isEqualByComparingTo("750.00");
        assertThat(accountService.getAccount(target.id().toString()).balance())
                .isEqualByComparingTo("250.00");

        Integer transferCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM transfers WHERE source_account_id = ? AND target_account_id = ?",
                Integer.class,
                source.id(),
                target.id()
        );
        assertThat(transferCount).isEqualTo(1);

        ReconciliationReportResponse recon = reconciliationService.reconcile();
        assertThat(recon.balanced()).isTrue();
    }

    @Test
    void conditionalUpdateStrategyAlsoPreservesConcurrentInvariants() throws Exception {
        AccountResponse accountA = accountService.createCustomerAccount(
                new CreateAccountRequest("Cond Concurrent A", "NAD"));
        AccountResponse accountB = accountService.createCustomerAccount(
                new CreateAccountRequest("Cond Concurrent B", "NAD"));

        ledgerService.deposit(
                accountA.id().toString(),
                new DepositRequest(new BigDecimal("2500.00"), "NAD", "Cond Seed A"),
                "cond-seed-a-" + UUID.randomUUID()
        );

        BankingProperties conditionalProps = new BankingProperties(
                properties.supportedCurrencies(),
                TransferService.STRATEGY_CONDITIONAL_UPDATE,
                properties.idempotency(),
                properties.fx()
        );
        TransferService conditionalService = new TransferService(
                accountService, accountRepository, ledgerService, journalEntryRepository,
                postingRepository, transferRepository, fxQuoteService, idempotencyService, conditionalProps,
                jdbcTemplate, entityManager, clock
        );

        int attempts = 100;
        CountDownLatch startGate = new CountDownLatch(1);
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();

        try (ExecutorService executor = Executors.newFixedThreadPool(attempts)) {
            List<Future<Void>> futures = new ArrayList<>(attempts);
            for (int i = 0; i < attempts; i++) {
                final int idx = i;
                futures.add(executor.submit(() -> {
                    startGate.await();
                    try {
                        TransferResponse resp = conditionalService.createTransfer(
                                new CreateTransferRequest(
                                        accountA.accountNumber(),
                                        accountB.accountNumber(),
                                        new BigDecimal("50.00"),
                                        "NAD",
                                        "COND-DRAIN-" + idx,
                                        null
                                ),
                                "cond-drain-" + accountA.id() + "-" + idx
                        );
                        if (resp != null) {
                            succeeded.incrementAndGet();
                        }
                    } catch (InsufficientFundsException ex) {
                        insufficient.incrementAndGet();
                    }
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(succeeded.get()).isEqualTo(50);
        assertThat(insufficient.get()).isEqualTo(50);
        assertThat(accountService.getAccount(accountA.id().toString()).balance())
                .isEqualByComparingTo("0.00");
        assertThat(accountService.getAccount(accountB.id().toString()).balance())
                .isEqualByComparingTo("2500.00");

        ReconciliationReportResponse recon = reconciliationService.reconcile();
        assertThat(recon.balanced()).isTrue();
    }
}
