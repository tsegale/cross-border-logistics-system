package com.freightflow.banking.account;

import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.common.ApiExceptionHandler;
import com.freightflow.banking.common.UnsupportedCurrencyException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import(ApiExceptionHandler.class)
class AccountControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private com.freightflow.banking.journal.LedgerService ledgerService;

    @Test
    void createAccountReturns201AndLocationHeader() throws Exception {
        UUID id = UUID.fromString("01930000-0000-7000-8000-000000001000");
        Instant now = Instant.parse("2026-11-07T18:00:00Z");
        given(accountService.createCustomerAccount(any())).willReturn(
                new AccountResponse(id, "FF83NAD000001000", "CUST-NAM-01",
                        AccountType.CUSTOMER, "NAD", AccountStatus.ACTIVE, new BigDecimal("0.00"), now, now)
        );

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holderReference": "CUST-NAM-01",
                                  "currency": "NAD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.accountNumber").value("FF83NAD000001000"))
                .andExpect(jsonPath("$.currency").value("NAD"))
                .andExpect(jsonPath("$.balance").value(0.00));
    }

    @Test
    void validationErrorsReturnRfc9457ProblemDetailsWithPerFieldDetails() throws Exception {
        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holderReference": "",
                                  "currency": "INVALID"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.title").value("Request validation failed"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.length()").value(2))
                .andExpect(jsonPath("$.errors[0].field").value("currency"))
                .andExpect(jsonPath("$.errors[1].field").value("holderReference"));
    }

    @Test
    void mistypedAccountNumberReturns400ProblemDetailsNot404() throws Exception {
        given(accountService.getAccount("FF87NAD000000009"))
                .willThrow(new InvalidAccountNumberException("FF87NAD000000009",
                        "Account number 'FF87NAD000000009' failed ISO 7064 MOD 97-10 check-digit verification"));

        mockMvc.perform(get("/api/v1/accounts/FF87NAD000000009"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-account-number"))
                .andExpect(jsonPath("$.accountNumber").value("FF87NAD000000009"));
    }

    @Test
    void unsupportedCurrencyReturns400ProblemDetails() throws Exception {
        given(accountService.createCustomerAccount(any()))
                .willThrow(new UnsupportedCurrencyException("USD", List.of("NAD", "ZAR", "EUR")));

        mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holderReference": "CUST-NAM-01",
                                  "currency": "USD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unsupported-currency"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.supportedCurrencies[0]").value("EUR"));
    }

    @Test
    void mapsInsufficientFundsInactiveAccountAndDatabaseOutageToProblemDetails() throws Exception {
        given(accountService.getAccount("FF87NAD000000001"))
                .willThrow(new InsufficientFundsException(
                        "FF87NAD000000001", "NAD", new BigDecimal("10.00"), new BigDecimal("50.00")))
                .willThrow(new AccountNotActiveException("FF87NAD000000001", AccountStatus.FROZEN))
                .willThrow(new org.springframework.dao.DataAccessResourceFailureException("db down"));

        mockMvc.perform(get("/api/v1/accounts/FF87NAD000000001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/insufficient-funds"))
                .andExpect(jsonPath("$.availableBalance").value(10.00))
                .andExpect(jsonPath("$.requestedAmount").value(50.00));

        mockMvc.perform(get("/api/v1/accounts/FF87NAD000000001"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/account-not-active"))
                .andExpect(jsonPath("$.status").value("FROZEN"));

        mockMvc.perform(get("/api/v1/accounts/FF87NAD000000001"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/database-unavailable"));
    }
}
