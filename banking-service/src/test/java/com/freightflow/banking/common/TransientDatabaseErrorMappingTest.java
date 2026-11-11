package com.freightflow.banking.common;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.account.AccountController;
import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.journal.LedgerService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.TransactionSystemException;

import java.sql.SQLException;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import({ApiExceptionHandler.class, com.freightflow.banking.support.TestSecurity.class})
class TransientDatabaseErrorMappingTest {

    private static final String ACCOUNT = "FF87NAD000000001";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccountService accountService;

    @MockitoBean
    private LedgerService ledgerService;

    @ParameterizedTest
    @CsvSource({
            "55P03, 503, lock-timeout",
            "40P01, 409, transaction-conflict",
            "40001, 409, transaction-conflict"
    })
    void transientSqlStatesAreRetryableProblems(String sqlState, int httpStatus, String slug) throws Exception {
        given(accountService.getAccount(TestCallers.OPERATOR, ACCOUNT)).willThrow(
                new PessimisticLockingFailureException("db", new SQLException("db", sqlState)));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT))
                .andExpect(status().is(httpStatus))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/" + slug));
    }

    @Test
    void serializationFailureRaisedAtCommitIsAlsoRetryable() throws Exception {
        given(accountService.getAccount(TestCallers.OPERATOR, ACCOUNT)).willThrow(
                new TransactionSystemException("commit failed", new SQLException("could not serialize", "40001")));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/transaction-conflict"));
    }

    @Test
    void lockFailureWithoutSqlStateIsAConflict() throws Exception {
        given(accountService.getAccount(TestCallers.OPERATOR, ACCOUNT)).willThrow(new CannotAcquireLockException("no state"));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/transaction-conflict"));
    }

    @Test
    void unexpectedExceptionIsAnInternalErrorProblemWithoutDetailsLeaking() throws Exception {
        given(accountService.getAccount(TestCallers.OPERATOR, ACCOUNT)).willThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/internal-error"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    void constraintViolationIsA409WithoutSchemaDetails() throws Exception {
        given(accountService.getAccount(TestCallers.OPERATOR, ACCOUNT)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_secret_index\""));

        mockMvc.perform(get("/api/v1/accounts/" + ACCOUNT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/data-conflict"))
                .andExpect(jsonPath("$.detail").value(
                        "The request conflicts with data that already exists or changed concurrently."));
    }
}
