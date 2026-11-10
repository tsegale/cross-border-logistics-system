package com.freightflow.banking.fx;

import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FxRateIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void eurBaseListsEveryOtherSupportedCurrencyWithSpreadApplied() throws Exception {
        mockMvc.perform(get("/api/v1/fx-rates").param("base", "eur"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.base").value("EUR"))
                .andExpect(jsonPath("$.source").value("static-configuration"))
                .andExpect(jsonPath("$.rates.length()").value(2))
                .andExpect(jsonPath("$.rates[0].currency").value("NAD"))
                .andExpect(jsonPath("$.rates[0].midRate").value(20.0))
                .andExpect(jsonPath("$.rates[0].spread").value(0.005))
                .andExpect(jsonPath("$.rates[0].customerRate").value(19.9))
                .andExpect(jsonPath("$.rates[1].currency").value("ZAR"));
    }

    @Test
    void commonMonetaryAreaPairHasNoSpread() throws Exception {
        mockMvc.perform(get("/api/v1/fx-rates").param("base", "NAD"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates[?(@.currency == 'ZAR')].customerRate").value(1.0));
    }

    @Test
    void unsupportedBaseIsA400Problem() throws Exception {
        mockMvc.perform(get("/api/v1/fx-rates").param("base", "USD"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unsupported-currency"));
    }
}
