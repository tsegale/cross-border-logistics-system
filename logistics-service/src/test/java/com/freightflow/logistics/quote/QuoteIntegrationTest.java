package com.freightflow.logistics.quote;

import com.freightflow.logistics.quote.dto.QuoteResponse;
import com.freightflow.logistics.support.IntegrationTest;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class QuoteIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsSeaLclAndAirQuotesAcrossIncotermsAndSnapshotsTariff() throws Exception {
        // 1. SEA_LCL quote NAWVB -> DEHAM under FCA in EUR
        // 2 pallets of 120x100x150 cm = 3.6000 CBM; actual = 2 * 500 kg = 1,000 kg (1.0 t) -> W/M = 3.6000 WM_TON
        // NAWVB->DEHAM SEA_LCL tariff: 95.00 EUR/WM_TON, min 180.00, fuel 12.5%
        // baseFreight = 3.6 * 95 = 342.00 EUR; fuel = 342 * 0.125 = 42.75 EUR; FCA total = 384.75 EUR
        MvcResult fcaRes = mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "DEHAM",
                                  "mode": "SEA_LCL",
                                  "incoterm": "FCA",
                                  "packages": [
                                    {
                                      "lengthCm": 120,
                                      "widthCm": 100,
                                      "heightCm": 150,
                                      "weightKg": 500,
                                      "quantity": 2,
                                      "description": "Frozen hake fillets"
                                    }
                                  ],
                                  "currency": "EUR"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.origin").value("NAWVB"))
                .andExpect(jsonPath("$.destination").value("DEHAM"))
                .andExpect(jsonPath("$.mode").value("SEA_LCL"))
                .andExpect(jsonPath("$.incoterm").value("FCA"))
                .andExpect(jsonPath("$.billingUnit").value("WM_TON"))
                .andExpect(jsonPath("$.actualWeightKg").value(1000.0))
                .andExpect(jsonPath("$.volumeCbm").value(3.6))
                .andExpect(jsonPath("$.chargeableWeightKg").value(3600.0))
                .andExpect(jsonPath("$.chargeableUnits").value(3.6))
                .andExpect(jsonPath("$.baseFreightCharge").value(342.00))
                .andExpect(jsonPath("$.fuelSurcharge").value(42.75))
                .andExpect(jsonPath("$.originHandlingCharge").value(0.00))
                .andExpect(jsonPath("$.destinationHandlingCharge").value(0.00))
                .andExpect(jsonPath("$.importCustomsDutyCharge").doesNotExist())
                .andExpect(jsonPath("$.totalAmount").value(384.75))
                .andReturn();

        JsonNode fcaJson = JSON.readTree(fcaRes.getResponse().getContentAsString());
        String fcaQuoteId = fcaJson.get("id").asString();

        // 2. Same cargo under DDP in NAD (converted at 1 EUR = 20 NAD)
        // Includes originHandling (65 EUR = 1300 NAD), baseFreight (342 EUR = 6840 NAD),
        // fuel (42.75 EUR = 855 NAD) and destHandling (85 EUR = 1700 NAD). No duties: billed at cost under DDP.
        MvcResult ddpRes = mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "DEHAM",
                                  "mode": "SEA_LCL",
                                  "incoterm": "DDP",
                                  "packages": [
                                    {
                                      "lengthCm": 120,
                                      "widthCm": 100,
                                      "heightCm": 150,
                                      "weightKg": 500,
                                      "quantity": 2,
                                      "description": "Frozen hake fillets"
                                    }
                                  ],
                                  "currency": "NAD"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currency").value("NAD"))
                .andExpect(jsonPath("$.baseFreightCharge").value(6840.00))
                .andExpect(jsonPath("$.fuelSurcharge").value(855.00))
                .andExpect(jsonPath("$.originHandlingCharge").value(1300.00))
                .andExpect(jsonPath("$.destinationHandlingCharge").value(1700.00))
                .andExpect(jsonPath("$.importCustomsDutyCharge").doesNotExist())
                .andExpect(jsonPath("$.totalAmount").value(10695.00))
                .andExpect(jsonPath("$.lineItems.length()").value(4))
                .andExpect(jsonPath("$.notes[0]").value(QuoteResponse.DUTIES_AS_DISBURSEMENT))
                .andReturn();

        // 3. AIR quote NAWDH -> DEFRA under DAP where minimumCharge applies
        // 1 box of 40x30x30 cm = 36,000 cm³ -> volumetric = 6.000 kg; actual = 10.000 kg -> chargeable = 10.000 kg
        // 10 * 4.50 = 45.00 EUR < minimumCharge 210.00 EUR -> baseFreight = 210.00 EUR
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWDH",
                                  "destination": "DEFRA",
                                  "mode": "AIR",
                                  "incoterm": "DAP",
                                  "packages": [
                                    {
                                      "lengthCm": 40,
                                      "widthCm": 30,
                                      "heightCm": 30,
                                      "weightKg": 10,
                                      "quantity": 1,
                                      "description": "Cut diamonds sample"
                                    }
                                  ],
                                  "currency": "EUR"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.chargeableWeightKg").value(10.0))
                .andExpect(jsonPath("$.baseFreightCharge").value(210.00));

        // 4. Verify tariff snapshotting: mutating tariffs.rate_per_unit in DB does not alter GET /api/v1/quotes/{id}
        jdbcTemplate.update(
                "UPDATE tariffs SET rate_per_unit = 999.0000 WHERE origin_unlocode = 'NAWVB' AND destination_unlocode = 'DEHAM' AND mode = 'SEA_LCL'");
        try {
            MvcResult fetched = mockMvc.perform(get("/api/v1/quotes/{id}", fcaQuoteId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(fcaQuoteId))
                    .andExpect(jsonPath("$.totalAmount").value(384.75))
                    .andReturn();
            JsonNode fetchedJson = JSON.readTree(fetched.getResponse().getContentAsString());
            assertThat(fetchedJson.get("totalAmount").decimalValue()).isEqualByComparingTo(new BigDecimal("384.75"));
        } finally {
            jdbcTemplate.update(
                    "UPDATE tariffs SET rate_per_unit = 95.0000 WHERE origin_unlocode = 'NAWVB' AND destination_unlocode = 'DEHAM' AND mode = 'SEA_LCL'");
        }

        // 5. Validation of route errors: same origin & destination -> 422 same-origin-destination
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "NAWVB",
                                  "mode": "SEA_LCL",
                                  "incoterm": "EXW",
                                  "packages": [{"lengthCm": 50, "widthCm": 50, "heightCm": 50, "weightKg": 20, "quantity": 1}],
                                  "currency": "EUR"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/same-origin-destination"));

        // Unsupported route (e.g., NAWDH has no SEAport, so SEA_LCL NAWDH -> DEFRA has no tariff) -> 422 unsupported-route
        mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWDH",
                                  "destination": "DEFRA",
                                  "mode": "SEA_LCL",
                                  "incoterm": "EXW",
                                  "packages": [{"lengthCm": 50, "widthCm": 50, "heightCm": 50, "weightKg": 20, "quantity": 1}],
                                  "currency": "EUR"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unsupported-route"));

        // Unknown quote -> 404 quote-not-found
        mockMvc.perform(get("/api/v1/quotes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/quote-not-found"));
    }
}
