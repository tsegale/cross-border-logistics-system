package com.freightflow.logistics.common;

import com.freightflow.logistics.support.TestCallers;
import com.freightflow.logistics.quote.QuoteController;
import com.freightflow.logistics.quote.QuoteService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.SQLException;
import java.util.UUID;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuoteController.class)
@Import({ApiExceptionHandler.class, com.freightflow.logistics.support.TestSecurity.class})
class TransientDatabaseErrorMappingTest {

    private static final UUID QUOTE = UUID.fromString("01950000-0000-7000-8000-000000000999");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteService quoteService;

    @ParameterizedTest
    @CsvSource({
            "55P03, 503, lock-timeout",
            "40P01, 409, transaction-conflict",
            "40001, 409, transaction-conflict"
    })
    void transientSqlStatesAreRetryableProblems(String sqlState, int httpStatus, String slug) throws Exception {
        given(quoteService.getQuote(TestCallers.OPERATOR, QUOTE)).willThrow(
                new PessimisticLockingFailureException("db", new SQLException("db", sqlState)));

        mockMvc.perform(get("/api/v1/quotes/" + QUOTE))
                .andExpect(status().is(httpStatus))
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/" + slug));
    }

    @Test
    void unexpectedExceptionIsAnInternalErrorProblem() throws Exception {
        given(quoteService.getQuote(TestCallers.OPERATOR, QUOTE)).willThrow(new IllegalStateException("secret internals"));

        mockMvc.perform(get("/api/v1/quotes/" + QUOTE))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/internal-error"))
                .andExpect(jsonPath("$.detail").value("The request could not be completed."));
    }

    @Test
    void constraintViolationIsA409WithoutSchemaDetails() throws Exception {
        given(quoteService.getQuote(TestCallers.OPERATOR, QUOTE)).willThrow(new org.springframework.dao.DataIntegrityViolationException(
                "duplicate key value violates unique constraint \"uq_secret_index\""));

        mockMvc.perform(get("/api/v1/quotes/" + QUOTE))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/data-conflict"))
                .andExpect(jsonPath("$.detail").value(
                        "The request conflicts with data that already exists or changed concurrently."));
    }
}
