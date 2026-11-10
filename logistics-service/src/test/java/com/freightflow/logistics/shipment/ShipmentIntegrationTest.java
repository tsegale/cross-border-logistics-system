package com.freightflow.logistics.shipment;

import com.freightflow.logistics.idempotency.IdempotencyCleanupJob;
import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShipmentIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final String VALID_PAYER_ACCOUNT =
            ShipmentStateMachineUnitTest.buildValidAccountNumber("EUR", "000000001");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Test
    void booksIdempotentShipmentValidatesPayerAndQuoteAndHandlesCancellationAndPagination() throws Exception {
        String quoteId = createQuote("NAWVB", "DEHAM", "SEA_LCL", "FCA", "EUR");

        String createBody = """
                {
                  "quoteId": "%s",
                  "shipper": {
                    "name": "Namib Fisheries (Pty) Ltd",
                    "address": "Ben Amathila Ave 14, Walvis Bay, Namibia",
                    "contact": "exports@namibfisheries.na"
                  },
                  "consignee": {
                    "name": "Hanseatic Seafood GmbH",
                    "address": "Am Sandtorkai 48, 20457 Hamburg, Germany",
                    "contact": "imports@hanseatic-seafood.de"
                  },
                  "payerAccountNumber": "%s",
                  "goodsDescription": "Frozen Merluccius capensis fillets in 20kg master cartons",
                  "declaredValue": 18500.00,
                  "declaredValueCurrency": "EUR"
                }
                """.formatted(quoteId, VALID_PAYER_ACCOUNT);

        String idemKey = "ship-book-" + UUID.randomUUID();

        // 1. Create shipment -> 201 Created, ETag "0", AWAITING_PAYMENT
        MvcResult createRes = mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.quoteId").value(quoteId))
                .andExpect(jsonPath("$.status").value("AWAITING_PAYMENT"))
                .andExpect(jsonPath("$.payerAccountNumber").value(VALID_PAYER_ACCOUNT))
                .andExpect(jsonPath("$.declaredValue").value(18500.00))
                .andExpect(jsonPath("$.version").value(0))
                .andReturn();

        JsonNode created = JSON.readTree(createRes.getResponse().getContentAsString());
        String shipmentId = created.get("id").asString();
        String trackingNumber = created.get("trackingNumber").asString();
        assertThat(TrackingNumber.isValid(trackingNumber)).isTrue();

        // 2. Idempotent replay with same key & payload -> 201 Created, Idempotent-Replayed: true
        mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(shipmentId))
                .andExpect(jsonPath("$.trackingNumber").value(trackingNumber));

        // 3. Reusing same Idempotency-Key with different payload -> 422 idempotency-key-payload-mismatch
        String modifiedBody = createBody.replace("18500.00", "25000.00");
        mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", idemKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(modifiedBody))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-payload-mismatch"));

        // 4. Missing Idempotency-Key -> 400 idempotency-key-missing
        mockMvc.perform(post("/api/v1/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-missing"));

        // 5. Invalid payer account check digits -> 400 invalid-account-number
        String badPayerBody = createBody.replace(VALID_PAYER_ACCOUNT, "FF00EUR000000001");
        mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", "bad-payer-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(badPayerBody))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-account-number"));

        // 6. Expired quote -> 409 quote-expired
        String expiredQuoteId = createQuote("NAWVB", "DEHAM", "SEA_LCL", "FCA", "EUR");
        jdbcTemplate.update(
                "UPDATE freight_quotes SET valid_until = created_at - INTERVAL '1 hour' WHERE id = ?::uuid",
                expiredQuoteId);
        String expiredQuoteBody = createBody.replace(quoteId, expiredQuoteId);
        mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", "exp-quote-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(expiredQuoteBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/quote-expired"));

        // 7. Create two more shipments so we can test keyset pagination (total >= 3)
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/v1/shipments")
                            .header("Idempotency-Key", "ship-extra-" + i + "-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isCreated());
        }

        // Paginate with size=2 across all shipments and with status=AWAITING_PAYMENT
        Set<String> seenIds = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            var req = get("/api/v1/shipments")
                    .param("status", "AWAITING_PAYMENT")
                    .param("size", "2");
            if (cursor != null) {
                req = req.param("cursor", cursor);
            }
            MvcResult pageRes = mockMvc.perform(req)
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode pageJson = JSON.readTree(pageRes.getResponse().getContentAsString());
            for (JsonNode item : pageJson.get("items")) {
                String id = item.get("id").asString();
                assertThat(seenIds.add(id)).isTrue();
                assertThat(item.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
            }
            JsonNode nextNode = pageJson.get("nextCursor");
            cursor = (nextNode == null || nextNode.isNull()) ? null : nextNode.asString();
            pages++;
        } while (cursor != null && pages < 10);
        assertThat(seenIds).hasSizeGreaterThanOrEqualTo(3);

        // Invalid page size -> 400 invalid-cursor
        mockMvc.perform(get("/api/v1/shipments").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-cursor"));

        // 8. Fetch shipment by ID and verify ETag "0"
        mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andExpect(jsonPath("$.id").value(shipmentId));

        // 9. Cancel with stale If-Match "99" -> 412 precondition-failed
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipmentId)
                        .header(HttpHeaders.IF_MATCH, "\"99\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Customer postponed order\"}"))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/precondition-failed"));

        // 10. Cancel with matching If-Match "0" -> 200 OK, ETag "1", status CANCELLED
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipmentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Customer postponed order\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("Customer postponed order"))
                .andExpect(jsonPath("$.version").value(1));

        // 11. Attempting to cancel again with old ETag "0" -> 412 precondition-failed
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipmentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/precondition-failed"));

        // 12. Attempting to cancel an already CANCELLED shipment with current ETag "1" -> 409 invalid-shipment-state-transition
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipmentId)
                        .header(HttpHeaders.IF_MATCH, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-shipment-state-transition"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"))
                .andExpect(jsonPath("$.requestedStatus").value("CANCELLED"));

        // Exercise idempotency cleanup job
        cleanupJob.purgeExpiredKeys();
    }

    private String createQuote(String origin, String destination, String mode, String incoterm, String currency)
            throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "%s",
                                  "destination": "%s",
                                  "mode": "%s",
                                  "incoterm": "%s",
                                  "payerRole": "CONSIGNEE",
                                  "packages": [
                                    {
                                      "lengthCm": 120,
                                      "widthCm": 100,
                                      "heightCm": 150,
                                      "weightKg": 500,
                                      "quantity": 2
                                    }
                                  ],
                                  "indicativeCurrency": "%s"
                                }
                                """.formatted(origin, destination, mode, incoterm, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }
}
