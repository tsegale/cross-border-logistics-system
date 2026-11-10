package com.freightflow.logistics.quote;

import com.freightflow.logistics.quote.dto.QuoteResponse;
import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.util.UUID;

import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Quoting through the API on the seeded corridor tariffs, one behaviour per test. */
class QuoteIntegrationTest extends IntegrationTest {

    private static final String TWO_PALLETS =
            "[{\"lengthCm\":120,\"widthCm\":100,\"heightCm\":150,\"weightKg\":500,\"quantity\":2,\"description\":\"Frozen hake\"}]";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void seaLclIsBilledPerRevenueTonForTheConsigneesLegsUnderFca() throws Exception {
        mockMvc.perform(quote("NAWVB", "DEHAM", "SEA_LCL", "FCA", "CONSIGNEE", TWO_PALLETS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billingUnit").value("WM_TON"))
                .andExpect(jsonPath("$.actualWeightKg").value(1000.0))
                .andExpect(jsonPath("$.volumeCbm").value(3.6))
                .andExpect(jsonPath("$.chargeableUnits").value(3.6))
                .andExpect(jsonPath("$.currency").value("EUR"))
                .andExpect(jsonPath("$.billableLegs.length()").value(3))
                .andExpect(jsonPath("$.originHandlingCharge").value(0.00))
                .andExpect(jsonPath("$.baseFreightCharge").value(342.00))
                .andExpect(jsonPath("$.fuelSurcharge").value(42.75))
                .andExpect(jsonPath("$.destinationHandlingCharge").value(85.00))
                .andExpect(jsonPath("$.importClearanceCharge").value(55.00))
                .andExpect(jsonPath("$.totalAmount").value(524.75))
                .andExpect(jsonPath("$.notes[0]").value(QuoteResponse.DUTIES_EXCLUDED));
    }

    @Test
    void ddpForTheShipperBillsEveryLegAndStatesDutiesAreADisbursement() throws Exception {
        mockMvc.perform(quote("NAWVB", "DEHAM", "SEA_LCL", "DDP", "SHIPPER", TWO_PALLETS))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.lineItems.length()").value(5))
                .andExpect(jsonPath("$.totalAmount").value(589.75))
                .andExpect(jsonPath("$.importCustomsDutyCharge").doesNotExist())
                .andExpect(jsonPath("$.incotermAllocation.importDutiesPaidBy").value("SHIPPER"))
                .andExpect(jsonPath("$.notes[0]").value(QuoteResponse.DUTIES_AS_DISBURSEMENT));
    }

    @Test
    void airFreightBelowTheMinimumIsChargedTheMinimum() throws Exception {
        // 40 x 30 x 30 cm = 6.000 kg volumetric, 10 kg actual; 10 x 4.50 = 45.00 < minimum 210.00 EUR
        mockMvc.perform(quote("NAWDH", "DEFRA", "AIR", "DAP", "SHIPPER",
                        "[{\"lengthCm\":40,\"widthCm\":30,\"heightCm\":30,\"weightKg\":10,\"quantity\":1}]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.billingUnit").value("KG"))
                .andExpect(jsonPath("$.chargeableWeightKg").value(10.0))
                .andExpect(jsonPath("$.baseFreightCharge").value(210.00));
    }

    @Test
    void laterTariffChangesDoNotAlterAnIssuedQuote() throws Exception {
        String id = json(mockMvc.perform(quote("NAWVB", "DEHAM", "SEA_LCL", "FCA", "CONSIGNEE", TWO_PALLETS))
                .andExpect(status().isCreated()).andReturn()).get("id").asString();
        String lane = "origin_unlocode = 'NAWVB' AND destination_unlocode = 'DEHAM' AND mode = 'SEA_LCL'";
        jdbcTemplate.update("UPDATE tariffs SET rate_per_unit = 999.0000 WHERE " + lane);
        try {
            mockMvc.perform(get("/api/v1/quotes/{id}", id))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalAmount").value(524.75));
        } finally {
            jdbcTemplate.update("UPDATE tariffs SET rate_per_unit = 95.0000 WHERE " + lane);
        }
    }

    @Test
    void sameOriginAndDestinationIs422() throws Exception {
        mockMvc.perform(quote("NAWVB", "NAWVB", "SEA_LCL", "EXW", "CONSIGNEE", TWO_PALLETS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/same-origin-destination"));
    }

    @Test
    void laneWithoutATariffForTheModeIs422() throws Exception {
        // Windhoek is inland: no SEA_LCL tariff
        mockMvc.perform(quote("NAWDH", "DEFRA", "SEA_LCL", "EXW", "CONSIGNEE", TWO_PALLETS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/unsupported-route"));
    }

    @Test
    void exwAskedByTheShipperHasNothingToQuote() throws Exception {
        mockMvc.perform(quote("NAWVB", "DEHAM", "SEA_LCL", "EXW", "SHIPPER", TWO_PALLETS))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/nothing-to-quote"))
                .andExpect(jsonPath("$.payerRole").value("SHIPPER"));
    }

    @Test
    void unknownQuoteIs404() throws Exception {
        mockMvc.perform(get("/api/v1/quotes/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/quote-not-found"));
    }

    private static RequestBuilder quote(String origin, String destination, String mode, String incoterm,
                                        String payerRole, String packages) {
        return post("/api/v1/quotes")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"origin":"%s","destination":"%s","mode":"%s","incoterm":"%s","payerRole":"%s","packages":%s}
                        """.formatted(origin, destination, mode, incoterm, payerRole, packages));
    }
}
