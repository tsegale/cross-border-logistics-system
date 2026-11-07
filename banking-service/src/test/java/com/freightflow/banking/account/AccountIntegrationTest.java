package com.freightflow.banking.account;

import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AccountIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountRepository accountRepository;

    @Test
    void flywaySeedsNineSystemAccountsWithValidMod97Numbers() {
        for (String currency : List.of("NAD", "ZAR", "EUR")) {
            for (AccountType type : List.of(AccountType.CLEARING, AccountType.FREIGHT_REVENUE, AccountType.FX_POSITION)) {
                Account systemAccount = accountRepository.findByAccountTypeAndCurrency(type, currency)
                        .orElseThrow(() -> new AssertionError("Missing " + type + " for " + currency));
                assertThat(AccountNumber.isValid(systemAccount.getAccountNumber())).isTrue();
                assertThat(systemAccount.getStatus()).isEqualTo(AccountStatus.ACTIVE);
            }
        }
    }

    @Test
    void createsCustomerAccountWithTimeOrderedUuidV7AndFetchesByIdOrAccountNumber() throws Exception {
        MvcResult first = mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holderReference": "Namib Fisheries Pty Ltd",
                                  "currency": "NAD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("CUSTOMER"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.currency").value("NAD"))
                .andExpect(jsonPath("$.balance").value(0.00))
                .andReturn();

        JsonNode body = JSON.readTree(first.getResponse().getContentAsByteArray());
        UUID id = UUID.fromString(body.get("id").asString());
        String accountNumber = body.get("accountNumber").asString();

        assertThat(id.version()).isEqualTo(7);
        assertThat(AccountNumber.isValid(accountNumber)).isTrue();

        // Fetch by UUID
        mockMvc.perform(get("/api/v1/accounts/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountNumber").value(accountNumber))
                .andExpect(jsonPath("$.holderReference").value("Namib Fisheries Pty Ltd"));

        // Fetch by check-digit account number
        mockMvc.perform(get("/api/v1/accounts/" + accountNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));

        // Mistyped account number -> 400 invalid-account-number, NOT 404
        String mistyped = accountNumber.substring(0, 15) + (accountNumber.charAt(15) == '0' ? '1' : '0');
        mockMvc.perform(get("/api/v1/accounts/" + mistyped))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-account-number"));

        // Valid check-digit number that does not exist in DB -> 404 account-not-found
        String unallocatedValid = AccountNumber.generate("EUR", 999_999_999L);
        mockMvc.perform(get("/api/v1/accounts/" + unallocatedValid))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/account-not-found"));
    }
}
