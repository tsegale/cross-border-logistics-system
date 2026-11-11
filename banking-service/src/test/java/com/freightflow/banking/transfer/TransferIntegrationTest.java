package com.freightflow.banking.transfer;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.transfer.dto.CreateTransferRequest;
import com.freightflow.banking.transfer.dto.TransferResponse;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TransferIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.freightflow.banking.account.AccountService accountService;

    @Autowired
    private com.freightflow.banking.account.AccountRepository accountRepository;

    @Autowired
    private com.freightflow.banking.journal.LedgerService ledgerService;

    @Autowired
    private com.freightflow.banking.journal.JournalEntryRepository journalEntryRepository;

    @Autowired
    private com.freightflow.banking.journal.PostingRepository postingRepository;

    @Autowired
    private TransferRepository transferRepository;

    @Autowired
    private com.freightflow.banking.fx.FxQuoteService fxQuoteService;

    @Autowired
    private com.freightflow.banking.idempotency.IdempotencyService idempotencyService;

    @Autowired
    private com.freightflow.banking.config.BankingProperties properties;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private jakarta.persistence.EntityManager entityManager;

    @Autowired
    private Clock clock;

    @Test
    void sameCurrencyTransferIdempotencyAndValidationWorkEndToEnd() throws Exception {
        JsonNode source = createCustomerAccount("Walvis Exporter", "NAD");
        JsonNode target = createCustomerAccount("Windhoek Logistics", "NAD");
        String sourceNumber = source.get("accountNumber").asString();
        String targetNumber = target.get("accountNumber").asString();

        // Deposit 5,000.00 NAD into source
        mockMvc.perform(post("/api/v1/accounts/" + sourceNumber + "/deposits")
                        .header("Idempotency-Key", "dep-tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 5000.00,
                                  "currency": "NAD",
                                  "reference": "DEP-TX-INIT"
                                }
                                """))
                .andExpect(status().isCreated());

        // Execute transfer of 1,250.50 NAD
        String payload = """
                {
                  "sourceAccount": "%s",
                  "targetAccount": "%s",
                  "amount": 1250.50,
                  "currency": "NAD",
                  "reference": "INV-2026-001"
                }
                """.formatted(sourceNumber, targetNumber);

        MvcResult created = mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceAccountNumber").value(sourceNumber))
                .andExpect(jsonPath("$.targetAccountNumber").value(targetNumber))
                .andExpect(jsonPath("$.sourceAmount").value(1250.50))
                .andExpect(jsonPath("$.targetAmount").value(1250.50))
                .andExpect(jsonPath("$.postings.length()").value(2))
                .andReturn();

        JsonNode transferJson = JSON.readTree(created.getResponse().getContentAsByteArray());
        String transferId = transferJson.get("id").asString();

        // Replay same Idempotency-Key and body -> returns identical transfer ID
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-key-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(transferId));

        // GET /api/v1/transfers/{id}
        mockMvc.perform(get("/api/v1/transfers/" + transferId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(transferId))
                .andExpect(jsonPath("$.reference").value("INV-2026-001"));

        // Verify updated balances: source = 3,749.50, target = 1,250.50
        mockMvc.perform(get("/api/v1/accounts/" + sourceNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerBalance").value(3749.50));
        mockMvc.perform(get("/api/v1/accounts/" + targetNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerBalance").value(1250.50));

        // Insufficient funds -> 409
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-key-overdraft")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 9000.00,
                                  "currency": "NAD",
                                  "reference": "OVERDRAFT"
                                }
                                """.formatted(sourceNumber, targetNumber)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/insufficient-funds"));

        // Mistyped account number -> 400 invalid-account-number, NOT 404
        String mistyped = sourceNumber.substring(0, 15) + (sourceNumber.charAt(15) == '0' ? '1' : '0');
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-key-mistyped")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 100.00,
                                  "currency": "NAD",
                                  "reference": "MISTYPED"
                                }
                                """.formatted(mistyped, targetNumber)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-account-number"));

        // Same source and target -> 422
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-key-same-acct")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 100.00,
                                  "currency": "NAD",
                                  "reference": "SELF-TX"
                                }
                                """.formatted(sourceNumber, sourceNumber)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/same-account-transfer"));

        // fxQuoteId provided on same-currency transfer -> 422
        mockMvc.perform(post("/api/v1/transfers")
                        .header("Idempotency-Key", "tx-key-unneeded-fx")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "sourceAccount": "%s",
                                  "targetAccount": "%s",
                                  "amount": 100.00,
                                  "currency": "NAD",
                                  "reference": "UNNEEDED-FX",
                                  "fxQuoteId": "%s"
                                }
                                """.formatted(sourceNumber, targetNumber, UUID.randomUUID())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-quote-not-applicable"));

        // Unknown transfer -> 404
        mockMvc.perform(get("/api/v1/transfers/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/transfer-not-found"));
    }

    @Test
    void conditionalUpdateStrategyExecutesBothDirectionsAndRejectsOverdraft() throws Exception {
        JsonNode acctA = createCustomerAccount("Conditional A", "NAD");
        JsonNode acctB = createCustomerAccount("Conditional B", "NAD");
        String numA = acctA.get("accountNumber").asString();
        String numB = acctB.get("accountNumber").asString();

        mockMvc.perform(post("/api/v1/accounts/" + numA + "/deposits")
                        .header("Idempotency-Key", "dep-cond-a")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.00,
                                  "currency": "NAD",
                                  "reference": "DEP-COND-A"
                                }
                                """))
                .andExpect(status().isCreated());

        var conditionalProps = new com.freightflow.banking.config.BankingProperties(
                properties.supportedCurrencies(),
                TransferService.STRATEGY_CONDITIONAL_UPDATE,
                properties.idempotency(),
                properties.fx(),
                properties.ledger(),
                properties.systemAccounts()
        );
        TransferService conditionalService = new TransferService(
                accountService, accountRepository, ledgerService, journalEntryRepository,
                postingRepository, transferRepository, fxQuoteService, idempotencyService, conditionalProps,
                jdbcTemplate, entityManager, clock
        );

        // A -> B (source UUID < target UUID)
        TransferResponse ab = conditionalService.createTransfer(TestCallers.OPERATOR, new CreateTransferRequest(numA, numB, new BigDecimal("400.00"), "NAD", "COND-AB", null),
                "cond-key-ab"
        );
        assertThat(ab.sourceAmount()).isEqualByComparingTo("400.00");

        // B -> A (source UUID > target UUID)
        TransferResponse ba = conditionalService.createTransfer(TestCallers.OPERATOR, new CreateTransferRequest(numB, numA, new BigDecimal("150.00"), "NAD", "COND-BA", null),
                "cond-key-ba"
        );
        assertThat(ba.sourceAmount()).isEqualByComparingTo("150.00");

        // Overdraft rejected on B (balance is 250.00)
        assertThatThrownBy(() -> conditionalService.createTransfer(TestCallers.OPERATOR, new CreateTransferRequest(numB, numA, new BigDecimal("300.00"), "NAD", "COND-OVER", null),
                "cond-key-over"
        )).isInstanceOf(com.freightflow.banking.account.InsufficientFundsException.class);
    }

    private JsonNode createCustomerAccount(String holder, String currency) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "organizationId": "%s",
                                  "currency": "%s"
                                }
                                """.formatted(TestCallers.NAMIB, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(res.getResponse().getContentAsByteArray());
    }
}
