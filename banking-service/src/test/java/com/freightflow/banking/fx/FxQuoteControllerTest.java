package com.freightflow.banking.fx;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.common.ApiExceptionHandler;
import com.freightflow.banking.fx.dto.FxQuoteResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(FxQuoteController.class)
@Import({ApiExceptionHandler.class, com.freightflow.banking.support.TestSecurity.class})
class FxQuoteControllerTest {

    private static final UUID ID = UUID.fromString("01930000-0000-7000-8000-000000002000");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private FxQuoteService fxQuoteService;

    @Test
    void createdQuoteIs201WithLocation() throws Exception {
        given(fxQuoteService.createQuote(any(), any())).willReturn(new FxQuoteResponse(ID, "NAD", "EUR",
                new BigDecimal("50000.00"), new BigDecimal("2487.50"), new BigDecimal("0.05000000"),
                new BigDecimal("0.005000"), new BigDecimal("0.04975000"), Instant.parse("2026-11-10T10:01:00Z"),
                null, Instant.parse("2026-11-10T10:00:00Z")));

        mockMvc.perform(post("/api/v1/fx-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellCurrency\":\"NAD\",\"buyCurrency\":\"EUR\",\"sellAmount\":50000.00}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/api/v1/fx-quotes/" + ID))
                .andExpect(jsonPath("$.buyAmount").value(2487.50))
                .andExpect(jsonPath("$.rate").value(0.04975));
    }

    @Test
    void missingCurrenciesAreValidationErrors() throws Exception {
        mockMvc.perform(post("/api/v1/fx-quotes").contentType(MediaType.APPLICATION_JSON).content("{\"sellAmount\":-1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors.length()").value(3));
        verifyNoInteractions(fxQuoteService);
    }

    @Test
    void bothAmountsIsAnInvalidRequest() throws Exception {
        given(fxQuoteService.createQuote(any(), any()))
                .willThrow(new InvalidFxQuoteRequestException("Exactly one of sellAmount or buyAmount must be provided"));

        mockMvc.perform(post("/api/v1/fx-quotes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellCurrency\":\"NAD\",\"buyCurrency\":\"EUR\",\"sellAmount\":1,\"buyAmount\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-fx-quote-request"));
    }

    @Test
    void unknownQuoteIs404() throws Exception {
        given(fxQuoteService.getQuote(TestCallers.OPERATOR, ID)).willThrow(new FxQuoteNotFoundException(ID));

        mockMvc.perform(get("/api/v1/fx-quotes/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-quote-not-found"));
    }
}
