package com.freightflow.logistics.quote;

import com.freightflow.logistics.quote.dto.QuoteResponse;
import com.freightflow.logistics.support.BankingStub;
import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.json.JsonMapper;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Quotes are priced in the tariff currency; a figure in another currency is indicative and comes from
 * banking-service, which owns FX. Logistics keeps no rate table of its own.
 */
class IndicativeQuoteIntegrationTest extends IntegrationTest {

    /** banking-service's GET /api/v1/fx-rates?base=NAD, as its OpenAPI document describes it. */
    static final String NAD_RATES = """
            {
              "base": "NAD",
              "asOf": "2026-11-10T09:00:00Z",
              "source": "static-configuration",
              "rates": [
                {"currency": "EUR", "midRate": 0.05000000, "spread": 0.005000, "customerRate": 0.04975000},
                {"currency": "ZAR", "midRate": 1.00000000, "spread": 0.000000, "customerRate": 1.00000000}
              ]
            }
            """;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Test
    void indicativeTotalUsesBankingsCustomerRateAndIsStoredWithTheQuote() throws Exception {
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).willReturn(okJson(NAD_RATES)));

        // FCA for the consignee: 342.00 + 42.75 + 85.00 + 55.00 = 524.75 EUR; 524.75 / 0.04975 = 10547.74 NAD
        MvcResult created = mockMvc.perform(quote("NAD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.totalAmount").value(524.75))
                .andExpect(jsonPath("$.indicative.currency").value("NAD"))
                .andExpect(jsonPath("$.indicative.totalAmount").value(10547.74))
                .andExpect(jsonPath("$.indicative.rate").value("1 NAD = 0.04975000 EUR"))
                .andExpect(jsonPath("$.indicative.rateSource").value("static-configuration"))
                .andExpect(jsonPath("$.notes[1]").value(QuoteResponse.INDICATIVE_ONLY))
                .andReturn();

        // The figure the customer saw is part of the quote: a later rate change does not alter it.
        BankingStub.SERVER.resetAll();
        String id = JSON.readTree(created.getResponse().getContentAsString()).get("id").asString();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/quotes/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.indicative.totalAmount").value(10547.74));
    }

    @Test
    void bankingDownStillIssuesTheQuoteWithoutAnIndicativeTotal() throws Exception {
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates")).willReturn(aResponse().withStatus(503)));

        mockMvc.perform(quote("NAD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalAmount").value(524.75))
                .andExpect(jsonPath("$.indicative").doesNotExist())
                .andExpect(jsonPath("$.notes[1]").value(QuoteResponse.INDICATIVE_UNAVAILABLE));
    }

    @Test
    void bankingSlowerThanTheReadTimeoutStillIssuesTheQuote() throws Exception {
        BankingStub.SERVER.stubFor(get(urlPathEqualTo("/api/v1/fx-rates"))
                .willReturn(okJson(NAD_RATES).withFixedDelay(3_000)));

        mockMvc.perform(quote("NAD"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.indicative").doesNotExist())
                .andExpect(jsonPath("$.notes[1]").value(QuoteResponse.INDICATIVE_UNAVAILABLE));
    }

    @Test
    void indicativeCurrencyEqualToTheTariffCurrencyDoesNotCallBanking() throws Exception {
        mockMvc.perform(quote("EUR"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.indicative").doesNotExist())
                .andExpect(jsonPath("$.notes.length()").value(1));

        BankingStub.SERVER.verify(0, anyRequestedFor(anyUrl()));
    }

    private static org.springframework.test.web.servlet.RequestBuilder quote(String indicativeCurrency) {
        return post("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "origin": "NAWVB",
                          "destination": "DEHAM",
                          "mode": "SEA_LCL",
                          "incoterm": "FCA",
                          "payerRole": "CONSIGNEE",
                          "packages": [{"lengthCm": 120, "widthCm": 100, "heightCm": 150, "weightKg": 500, "quantity": 2}],
                          "indicativeCurrency": "%s"
                        }
                        """.formatted(indicativeCurrency));
    }
}
