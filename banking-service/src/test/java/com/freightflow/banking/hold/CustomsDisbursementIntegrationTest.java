package com.freightflow.banking.hold;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.TestCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Duties and import VAT are not revenue (ADR 0036): the capture splits freight from the duties and taxes collected
 * with it in one entry, and the settlement pays customs what it assessed and settles the difference with the payer.
 * The database is shared, so system accounts are checked by how much they moved.
 */
class CustomsDisbursementIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String REVENUE_EUR = "01930000-0000-7000-8000-000000000008";
    private static final String CUSTOMS_EUR = "01930000-0000-7000-8000-000000000012";
    private static final String CLEARING_EUR = "01930000-0000-7000-8000-000000000007";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void theCaptureSplitsFreightFromDutiesAndAHigherAssessmentChargesTheDifference() throws Exception {
        AccountResponse payer = funded("EUR", "10000.00");
        BigDecimal revenue = balance(REVENUE_EUR);
        BigDecimal customs = balance(CUSTOMS_EUR);
        BigDecimal clearing = balance(CLEARING_EUR);
        // Freight 2,560.00 plus the estimate of 3,528.52 for duties and import VAT.
        String hold = hold(payer, "6088.52", "EUR");

        mockMvc.perform(capture(hold, "{\"disbursementAmount\":3528.52}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capturedAmount").value(6088.52))
                .andExpect(jsonPath("$.capturedDisbursementAmount").value(3528.52));
        assertThat(balance(REVENUE_EUR).subtract(revenue)).as("only the freight is revenue").isEqualByComparingTo("2560.00");
        assertThat(balance(CUSTOMS_EUR).subtract(customs)).isEqualByComparingTo("3528.52");

        // Customs assesses 3,611.34: 82.82 more than the estimate, charged to the payer.
        mockMvc.perform(settle(hold, "3611.34", "26DE470100123456E1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estimate").value(3528.52))
                .andExpect(jsonPath("$.difference").value(82.82))
                .andExpect(jsonPath("$.payerDifference").value(82.82));
        assertThat(balance(CUSTOMS_EUR).subtract(customs)).as("the liability is paid off").isEqualByComparingTo("0");
        assertThat(balance(CLEARING_EUR).subtract(clearing)).as("paid to the authority").isEqualByComparingTo("3611.34");
        assertThat(balance(REVENUE_EUR).subtract(revenue)).as("revenue untouched").isEqualByComparingTo("2560.00");
        assertThat(accounts.getAccount(TestCallers.OPERATOR, payer.id().toString()).ledgerBalance())
                .isEqualByComparingTo(new BigDecimal("10000.00").subtract(new BigDecimal("6088.52"))
                        .subtract(new BigDecimal("82.82")));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT j.entry_type FROM customs_settlements s JOIN journal_entries j ON j.id = s.journal_entry_id
                WHERE s.hold_id = ?""", String.class, UUID.fromString(hold))).isEqualTo("CUSTOMS_SETTLEMENT");

        // Once only, whatever the key.
        mockMvc.perform(settle(hold, "3611.34", "26DE470100123456E1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/customs-already-settled"));
    }

    @Test
    void aLowerAssessmentRefundsANadPayerAtTheHoldsLockedRate() throws Exception {
        // The static test rates: 1 NAD = 0.05 EUR mid, 0.04975 to the customer; 1,000.00 EUR is 20,100.50 NAD.
        AccountResponse payer = funded("NAD", "50000.00");
        String hold = hold(payer, "1000.00", "EUR");
        mockMvc.perform(capture(hold, "{\"disbursementAmount\":400.00}")).andExpect(status().isOk());
        BigDecimal afterCapture = accounts.getAccount(TestCallers.OPERATOR, payer.id().toString()).ledgerBalance();

        // Assessed 350.00: 50.00 EUR back, 1,005.03 NAD at the locked 0.04975 (50 / 0.04975 = 1,005.0251).
        mockMvc.perform(settle(hold, "350.00", "26DE470100654321E2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.difference").value(-50.00))
                .andExpect(jsonPath("$.payerDifference").value(-1005.03))
                .andExpect(jsonPath("$.payerCurrency").value("NAD"));
        assertThat(accounts.getAccount(TestCallers.OPERATOR, payer.id().toString()).ledgerBalance().subtract(afterCapture))
                .isEqualByComparingTo("1005.03");
    }

    @Test
    void customsIsSettledOnlyForACapturedHold() throws Exception {
        String hold = hold(funded("EUR", "500.00"), "100.00", "EUR");

        mockMvc.perform(settle(hold, "10.00", "26DE470100000000E3"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/hold-not-captured"));
    }

    @Test
    void aDisbursementLargerThanTheCaptureIsRefused() throws Exception {
        String hold = hold(funded("EUR", "500.00"), "100.00", "EUR");

        mockMvc.perform(capture(hold, "{\"amount\":100.00,\"disbursementAmount\":100.01}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("more than the captured")));
    }

    private AccountResponse funded(String currency, String amount) {
        AccountResponse account = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest(currency, TestCallers.NAMIB));
        funding.deposit(account.id().toString(), new BigDecimal(amount), currency, "SEED");
        return account;
    }

    private String hold(AccountResponse payer, String amount, String currency) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/holds").with(TestCallers.logistics())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerOrganizationId":"%s","payerAccount":"%s","amount":%s,"currency":"%s","reference":"SHIPMENT FF-CUSTOMS"}
                                """.formatted(TestCallers.NAMIB, payer.accountNumber(), amount, currency)))
                .andExpect(status().isCreated()).andReturn();
        return json(result).get("id").asString();
    }

    private static org.springframework.test.web.servlet.RequestBuilder capture(String hold, String body) {
        return post("/api/v1/holds/{id}/capture", hold).with(TestCallers.logistics())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static org.springframework.test.web.servlet.RequestBuilder settle(String hold, String assessed, String reference) {
        return post("/api/v1/holds/{id}/customs-settlement", hold).with(TestCallers.logistics())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"assessedAmount\":" + assessed + ",\"authorityReference\":\"" + reference + "\"}");
    }

    private BigDecimal balance(String accountId) {
        return accounts.getAccount(TestCallers.OPERATOR, accountId).ledgerBalance();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
