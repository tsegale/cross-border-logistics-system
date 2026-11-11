package com.freightflow.banking.hold;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.reconciliation.ReconciliationService;
import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authorise, capture and release (ADR 0011), through the HTTP API, each test ending on a clean ledger. */
class HoldIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
    private static final UUID FREIGHT_REVENUE_EUR = UUID.fromString("01930000-0000-7000-8000-000000000008");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private ReconciliationService reconciliationService;

    @Autowired
    private HoldExpirySweeper sweeper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void authorisingReducesTheAvailableBalanceButNotTheLedgerBalance() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");

        mockMvc.perform(hold(payer, "524.75", "EUR", key()))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.payerAmount").value(524.75))
                .andExpect(jsonPath("$.fxRate").doesNotExist());

        assertBalances(payer, "1000.00", "475.25");
        assertClean();
    }

    @Test
    void sameKeyReplaysTheHoldAndAnotherBodyIs422() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String key = key();
        String id = json(mockMvc.perform(hold(payer, "100.00", "EUR", key)).andExpect(status().isCreated()).andReturn())
                .get("id").asString();

        mockMvc.perform(hold(payer, "100.00", "EUR", key))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(id));
        mockMvc.perform(hold(payer, "200.00", "EUR", key))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-payload-mismatch"));
        assertBalances(payer, "1000.00", "900.00");
    }

    @Test
    void holdsCannotExceedTheAvailableBalance() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        mockMvc.perform(hold(payer, "700.00", "EUR", key())).andExpect(status().isCreated());

        mockMvc.perform(hold(payer, "300.01", "EUR", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/insufficient-funds"))
                .andExpect(jsonPath("$.availableBalance").value(300.00));
    }

    @Test
    void transfersAreCheckedAgainstTheAvailableBalance() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        AccountResponse other = accountService.createCustomerAccount(new CreateAccountRequest("Other", "EUR"));
        mockMvc.perform(hold(payer, "900.00", "EUR", key())).andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/transfers").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sourceAccount":"%s","targetAccount":"%s","amount":100.01,"currency":"EUR","reference":"T"}
                                """.formatted(payer.accountNumber(), other.accountNumber())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/insufficient-funds"));
        assertClean();
    }

    @Test
    void systemAccountsCannotBeHeld() throws Exception {
        mockMvc.perform(post("/api/v1/holds").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccount":"FF14EUR000000001","amount":1.00,"currency":"EUR","reference":"X"}
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-hold"));
    }

    @Test
    void fullCapturePostsPayerToFreightRevenueAndClosesTheHold() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        BigDecimal revenueBefore = accountService.getAccount(FREIGHT_REVENUE_EUR.toString()).ledgerBalance();
        String id = holdId(payer, "524.75", "EUR");

        JsonNode captured = json(mockMvc.perform(capture(id, null, key()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CAPTURED"))
                .andExpect(jsonPath("$.capturedAmount").value(524.75))
                .andReturn());

        assertBalances(payer, "475.25", "475.25");
        assertThat(accountService.getAccount(FREIGHT_REVENUE_EUR.toString()).ledgerBalance())
                .isEqualByComparingTo(revenueBefore.add(new BigDecimal("524.75")));
        assertThat(jdbcTemplate.queryForObject("SELECT entry_type FROM journal_entries WHERE id = ?::uuid",
                String.class, captured.get("captureJournalEntryId").asString())).isEqualTo("CAPTURE");
        assertClean();
    }

    @Test
    void partialCaptureReleasesTheRestAndHappensOnce() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String id = holdId(payer, "524.75", "EUR");

        mockMvc.perform(capture(id, "400.00", key())).andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAmount").value(400.00));
        assertBalances(payer, "600.00", "600.00");

        mockMvc.perform(capture(id, "100.00", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/hold-not-active"))
                .andExpect(jsonPath("$.status").value("CAPTURED"));
        assertClean();
    }

    @Test
    void captureNeverExceedsItsHold() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String id = holdId(payer, "100.00", "EUR");

        mockMvc.perform(capture(id, "100.01", key()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/capture-exceeds-hold"));
        assertBalances(payer, "1000.00", "900.00");
    }

    @Test
    void releaseFreesTheReservationPostsNothingAndHappensOnce() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String id = holdId(payer, "250.00", "EUR");
        Integer postingsBefore = postingsOf(payer);

        mockMvc.perform(release(id, key())).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));

        assertBalances(payer, "1000.00", "1000.00");
        assertThat(postingsOf(payer)).isEqualTo(postingsBefore);
        mockMvc.perform(release(id, key())).andExpect(status().isConflict());
        mockMvc.perform(capture(id, null, key())).andExpect(status().isConflict());
        assertClean();
    }

    @Test
    void retriedCaptureWithTheSameKeyReplaysInsteadOfFailing() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String id = holdId(payer, "100.00", "EUR");
        String key = key();

        mockMvc.perform(capture(id, null, key)).andExpect(status().isOk());
        mockMvc.perform(capture(id, null, key)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CAPTURED"));
        assertBalances(payer, "900.00", "900.00");
    }

    @Test
    void concurrentCaptureAndReleaseOfOneHoldResolveToExactlyOne() throws Exception {
        AccountResponse payer = funded("EUR", "10000.00");
        int rounds = 10;
        int captures = 0;
        for (int i = 0; i < rounds; i++) {
            String id = holdId(payer, "100.00", "EUR");
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
                Future<MvcResult> c = pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(capture(id, null, key())).andReturn();
                });
                Future<MvcResult> r = pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(release(id, key())).andReturn();
                });
                start.countDown();
                List<Integer> statuses = List.of(c.get().getResponse().getStatus(), r.get().getResponse().getStatus());
                assertThat(statuses).containsExactlyInAnyOrder(200, 409);
                if (c.get().getResponse().getStatus() == 200) {
                    captures++;
                }
            }
        }
        BigDecimal expected = new BigDecimal("10000.00").subtract(new BigDecimal("100.00").multiply(BigDecimal.valueOf(captures)));
        assertBalances(payer, expected.toPlainString(), expected.toPlainString());
        assertClean();
    }

    @Test
    void concurrentHoldsNeverOverdrawTheAccount() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        int attempts = 20;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(attempts)) {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(hold(payer, "100.00", "EUR", key())).andReturn();
                }));
            }
            start.countDown();
            int created = 0;
            for (Future<MvcResult> f : futures) {
                if (f.get().getResponse().getStatus() == 201) {
                    created++;
                }
            }
            assertThat(created).isEqualTo(10);
        }
        assertBalances(payer, "1000.00", "0.00");
        assertClean();
    }

    @Test
    void crossCurrencyHoldLocksBankingsRateAndCapturesThroughFxPositions() throws Exception {
        AccountResponse payer = funded("NAD", "20000.00");

        // 524.75 EUR / 0.04975 = 10547.74 NAD at banking's customer rate (mid 0.05, spread 0.5%)
        String id = json(mockMvc.perform(hold(payer, "524.75", "EUR", key()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payerAmount").value(10547.74))
                .andExpect(jsonPath("$.payerCurrency").value("NAD"))
                .andExpect(jsonPath("$.fxRate").value(0.04975))
                .andReturn()).get("id").asString();
        assertBalances(payer, "20000.00", "9452.26");

        JsonNode captured = json(mockMvc.perform(capture(id, null, key())).andExpect(status().isOk()).andReturn());
        String entry = captured.get("captureJournalEntryId").asString();
        mockMvc.perform(get("/api/v1/journal-entries/{id}", entry))
                .andExpect(jsonPath("$.postings.length()").value(4))
                .andExpect(jsonPath("$.fxRate").value(0.04975));
        assertBalances(payer, "9452.26", "9452.26");
        assertClean();
    }

    @Test
    void expiredHoldIsReleasedBySweepersRacingOnAllInstancesOnce() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String id = json(mockMvc.perform(post("/api/v1/holds").header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccount":"%s","amount":300.00,"currency":"EUR","reference":"EXP","expiresAt":"%s"}
                                """.formatted(payer.accountNumber(), Instant.now().plusMillis(1500))))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        Thread.sleep(1_700);

        mockMvc.perform(capture(id, null, key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/hold-expired"));

        int[] expired = new int[2];
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> a = pool.submit(sweeper::sweep);
            Future<Integer> b = pool.submit(sweeper::sweep);
            expired[0] = a.get();
            expired[1] = b.get();
        }
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM holds WHERE id = ?::uuid", String.class, id))
                .isEqualTo("EXPIRED");
        assertBalances(payer, "1000.00", "1000.00");
        assertClean();
    }

    @Test
    void expiryOutsideTheAllowedWindowIsRejected() throws Exception {
        AccountResponse payer = funded("EUR", "100.00");
        mockMvc.perform(post("/api/v1/holds").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccount":"%s","amount":1.00,"currency":"EUR","reference":"X","expiresAt":"%s"}
                                """.formatted(payer.accountNumber(), Instant.now().plusSeconds(31L * 24 * 3600))))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-hold"));
    }

    @Test
    void holdsAreFoundByTheirReference() throws Exception {
        AccountResponse payer = funded("EUR", "1000.00");
        String reference = "SHIPMENT " + UUID.randomUUID();
        mockMvc.perform(post("/api/v1/holds").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerAccount":"%s","amount":10.00,"currency":"EUR","reference":"%s"}
                                """.formatted(payer.accountNumber(), reference)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/holds").param("reference", reference))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].reference").value(reference));
        mockMvc.perform(get("/api/v1/holds").param("reference", "SHIPMENT nobody"))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void unknownHoldIs404() throws Exception {
        mockMvc.perform(get("/api/v1/holds/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/hold-not-found"));
    }

    // ------------------------------------------------------------------ helpers

    private AccountResponse funded(String currency, String amount) {
        AccountResponse account = accountService.createCustomerAccount(new CreateAccountRequest("Hold payer", currency));
        ledgerService.deposit(account.id().toString(), new DepositRequest(new BigDecimal(amount), currency, "SEED"), key());
        return account;
    }

    private String holdId(AccountResponse payer, String amount, String currency) throws Exception {
        return json(mockMvc.perform(hold(payer, amount, currency, key())).andExpect(status().isCreated()).andReturn())
                .get("id").asString();
    }

    private static RequestBuilder hold(AccountResponse payer, String amount, String currency, String key) {
        return post("/api/v1/holds").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"payerAccount":"%s","amount":%s,"currency":"%s","reference":"SHIPMENT FF-TEST"}
                        """.formatted(payer.accountNumber(), amount, currency));
    }

    private static RequestBuilder capture(String holdId, String amount, String key) {
        var builder = post("/api/v1/holds/{id}/capture", holdId).header("Idempotency-Key", key);
        return amount == null ? builder
                : builder.contentType(MediaType.APPLICATION_JSON).content("{\"amount\":" + amount + "}");
    }

    private static RequestBuilder release(String holdId, String key) {
        return post("/api/v1/holds/{id}/release", holdId).header("Idempotency-Key", key);
    }

    private void assertBalances(AccountResponse account, String ledger, String available) {
        AccountResponse current = accountService.getAccount(account.id().toString());
        assertThat(current.ledgerBalance()).as("ledger balance").isEqualByComparingTo(ledger);
        assertThat(current.availableBalance()).as("available balance").isEqualByComparingTo(available);
    }

    private void assertClean() {
        var report = reconciliationService.reconcile();
        assertThat(report.discrepancies()).isEmpty();
        assertThat(report.balanced()).isTrue();
    }

    private Integer postingsOf(AccountResponse account) {
        return jdbcTemplate.queryForObject("SELECT count(*) FROM postings WHERE account_id = ?", Integer.class, account.id());
    }

    private static String key() {
        return "hold-test-" + UUID.randomUUID();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
