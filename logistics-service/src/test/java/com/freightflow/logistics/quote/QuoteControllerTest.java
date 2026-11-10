package com.freightflow.logistics.quote;

import com.freightflow.logistics.common.ApiExceptionHandler;
import com.freightflow.logistics.common.UnsupportedCurrencyException;
import com.freightflow.logistics.location.UnknownLocationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(QuoteController.class)
@Import(ApiExceptionHandler.class)
class QuoteControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private QuoteService quoteService;

    @Test
    void returnsProblemDetailsForValidationErrorsAndDomainExceptions() throws Exception {
        // Missing required fields -> 400 validation-failed
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/validation-failed"))
                .andExpect(jsonPath("$.errors").isArray());

        // Malformed enum in JSON -> 400 invalid-request-body
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "DEHAM",
                                  "mode": "ROCKET",
                                  "incoterm": "FCA",
                                  "payerRole": "CONSIGNEE",
                                  "packages": [{"lengthCm": 50, "widthCm": 50, "heightCm": 50, "weightKg": 20, "quantity": 1}]
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-request-body"));

        // Unknown location -> 422 unknown-location
        doThrow(new UnknownLocationException("origin", "XXXXX"))
                .when(quoteService).createQuote(any());
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "XXXXX",
                                  "destination": "DEHAM",
                                  "mode": "SEA_LCL",
                                  "incoterm": "FCA",
                                  "payerRole": "CONSIGNEE",
                                  "packages": [{"lengthCm": 50, "widthCm": 50, "heightCm": 50, "weightKg": 20, "quantity": 1}]
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unknown-location"))
                .andExpect(jsonPath("$.unlocode").value("XXXXX"));

        // Unsupported currency -> 400 unsupported-currency
        doThrow(new UnsupportedCurrencyException("USD", Set.of("NAD", "ZAR", "EUR")))
                .when(quoteService).createQuote(any());
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "DEHAM",
                                  "mode": "SEA_LCL",
                                  "incoterm": "FCA",
                                  "payerRole": "CONSIGNEE",
                                  "packages": [{"lengthCm": 50, "widthCm": 50, "heightCm": 50, "weightKg": 20, "quantity": 1}],
                                  "indicativeCurrency": "USD"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unsupported-currency"));

        // Database unavailable -> 503 with Retry-After: 5
        UUID id = UUID.randomUUID();
        doThrow(new DataAccessResourceFailureException("DB down"))
                .when(quoteService).getQuote(id);
        mockMvc.perform(get("/api/v1/quotes/{id}", id))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/database-unavailable"));
    }
}
