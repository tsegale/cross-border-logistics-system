package com.freightflow.banking.fx;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.support.IntegrationTest;
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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FxTransferIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void executesFourPostingCrossCurrencyTransferViaFxPositionAccounts() throws Exception {
        AccountResponse nadAccount = accountService.createCustomerAccount(
                new CreateAccountRequest("Walvis Bay Shipper", "NAD"));
        AccountResponse eurAccount = accountService.createCustomerAccount(
                new CreateAccountRequest("Hamburg Forwarder", "EUR"));

        ledgerService.deposit(
                nadAccount.id().toString(),
                new DepositRequest(new BigDecimal("10000.00"), "NAD", "Initial NAD deposit"),
                "fx-dep-" + UUID.randomUUID()
        );

        // Request an FX quote to buy 200.00 EUR using NAD
        // Effective rate NAD->EUR = 0.04975000 => sellAmount = 200 / 0.04975 = 4020.1005 -> HALF_EVEN 4020.10 NAD
        MvcResult quoteResult = mockMvc.perform(post("/api/v1/fx-quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellCurrency": "NAD",
                                  "buyCurrency": "EUR",
                                  "buyAmount": 200.00
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sellCurrency").value("NAD"))
                .andExpect(jsonPath("$.buyCurrency").value("EUR"))
                .andExpect(jsonPath("$.buyAmount").value(200.00))
                .andExpect(jsonPath("$.sellAmount").value(4020.10))
                .andExpect(jsonPath("$.rate").value(0.04975))
                .andReturn();

        JsonNode quoteJson = JSON.readTree(quoteResult.getResponse().getContentAsString());
        String quoteId = quoteJson.get("id").asString();

        mockMvc.perform(get("/api/v1/fx-quotes/{id}", quoteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(quoteId))
                .andExpect(jsonPath("$.usedAt").isEmpty());

        // Execute cross-currency transfer referencing the FX quote
        MvcResult transferResult = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "fx-tx-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 200.00,
                                  "currency": "EUR",
                                  "reference": "Corridor freight settlement NAWVB-DEHAM",
                                  "fxQuoteId": "%s"
                                }
                                """.formatted(nadAccount.accountNumber(), eurAccount.accountNumber(), quoteId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceCurrency").value("NAD"))
                .andExpect(jsonPath("$.sourceAmount").value(4020.10))
                .andExpect(jsonPath("$.targetCurrency").value("EUR"))
                .andExpect(jsonPath("$.targetAmount").value(200.00))
                .andExpect(jsonPath("$.fxQuoteId").value(quoteId))
                .andExpect(jsonPath("$.postings.length()").value(4))
                .andReturn();

        JsonNode transferJson = JSON.readTree(transferResult.getResponse().getContentAsString());
        String journalEntryId = transferJson.get("journalEntryId").asString();

        // Verify the 4 postings sum to zero per currency in the database
        BigDecimal nadNet = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE journal_entry_id = ? AND currency = 'NAD'",
                BigDecimal.class,
                UUID.fromString(journalEntryId)
        );
        BigDecimal eurNet = jdbcTemplate.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM postings WHERE journal_entry_id = ? AND currency = 'EUR'",
                BigDecimal.class,
                UUID.fromString(journalEntryId)
        );
        assertThat(nadNet).isEqualByComparingTo("0.0000");
        assertThat(eurNet).isEqualByComparingTo("0.0000");

        // Verify customer balances
        assertThat(accountService.getAccount(nadAccount.id().toString()).ledgerBalance())
                .isEqualByComparingTo("5979.90");
        assertThat(accountService.getAccount(eurAccount.id().toString()).ledgerBalance())
                .isEqualByComparingTo("200.00");

        // Quote is now marked used; attempting a second transfer with the same fxQuoteId fails with 409 fx-quote-already-used
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "fx-tx-reuse-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 200.00,
                                  "currency": "EUR",
                                  "reference": "Replay with consumed quote",
                                  "fxQuoteId": "%s"
                                }
                                """.formatted(nadAccount.accountNumber(), eurAccount.accountNumber(), quoteId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-quote-already-used"));
    }

    @Test
    void rejectsExpiredAndMismatchedFxQuotesAndValidatesQuoteCreation() throws Exception {
        AccountResponse nadAccount = accountService.createCustomerAccount(
                new CreateAccountRequest("NAD Holder", "NAD"));
        AccountResponse zarAccount = accountService.createCustomerAccount(
                new CreateAccountRequest("ZAR Holder", "ZAR"));

        ledgerService.deposit(
                nadAccount.id().toString(),
                new DepositRequest(new BigDecimal("1000.00"), "NAD", "NAD funding"),
                "fx-dep-" + UUID.randomUUID()
        );

        // Both sellAmount and buyAmount provided -> 400 invalid-fx-quote-request
        mockMvc.perform(post("/api/v1/fx-quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellCurrency": "NAD",
                                  "buyCurrency": "ZAR",
                                  "sellAmount": 100.00,
                                  "buyAmount": 100.00
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-fx-quote-request"));

        // Create a valid 1:1 NAD -> ZAR quote for 250.00 NAD
        MvcResult quoteResult = mockMvc.perform(post("/api/v1/fx-quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sellCurrency": "NAD",
                                  "buyCurrency": "ZAR",
                                  "sellAmount": 250.00
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.buyAmount").value(250.00))
                .andExpect(jsonPath("$.rate").value(1.0))
                .andReturn();
        String quoteId = JSON.readTree(quoteResult.getResponse().getContentAsString()).get("id").asString();

        // Mismatched transfer amount -> 409 fx-quote-mismatch
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "fx-mismatch-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 300.00,
                                  "currency": "NAD",
                                  "reference": "Wrong amount",
                                  "fxQuoteId": "%s"
                                }
                                """.formatted(nadAccount.accountNumber(), zarAccount.accountNumber(), quoteId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-quote-mismatch"));

        // Backdate expires_at in DB to simulate quote expiration
        jdbcTemplate.update(
                "UPDATE fx_quotes SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(10)),
                UUID.fromString(quoteId)
        );

        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "fx-expired-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 250.00,
                                  "currency": "NAD",
                                  "reference": "Expired quote",
                                  "fxQuoteId": "%s"
                                }
                                """.formatted(nadAccount.accountNumber(), zarAccount.accountNumber(), quoteId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-quote-expired"));

        // Unknown FX quote -> 404 fx-quote-not-found
        mockMvc.perform(get("/api/v1/fx-quotes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-quote-not-found"));
    }
}
