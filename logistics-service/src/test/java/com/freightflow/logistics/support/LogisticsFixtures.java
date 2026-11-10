package com.freightflow.logistics.support;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Builds quotes, shipments and tracking events through the HTTP API, so each focused test stays short. */
public final class LogisticsFixtures {

    public static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    public static final String PAYER_ACCOUNT = accountNumber("EUR", "000000002");

    /** A FreightFlow account number with valid ISO 7064 MOD 97-10 check digits. */
    public static String accountNumber(String currency, String nineDigits) {
        String rearranged = currency + nineDigits + "FF00";
        long remainder = 0L;
        for (int i = 0; i < rearranged.length(); i++) {
            char ch = rearranged.charAt(i);
            remainder = Character.isDigit(ch)
                    ? (remainder * 10L + (ch - '0')) % 97L
                    : (remainder * 100L + (ch - 'A' + 10)) % 97L;
        }
        return String.format("FF%02d%s%s", 98L - remainder, currency, nineDigits);
    }

    private final MockMvc mockMvc;

    public LogisticsFixtures(MockMvc mockMvc) {
        this.mockMvc = mockMvc;
    }

    /** FCA for the consignee, NAWVB to DEHAM, 2 pallets: 524.75 EUR. */
    public String createQuote() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "DEHAM",
                                  "mode": "SEA_LCL",
                                  "incoterm": "FCA",
                                  "payerRole": "CONSIGNEE",
                                  "packages": [{"lengthCm": 120, "widthCm": 100, "heightCm": 150, "weightKg": 500, "quantity": 2}]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return json(res).get("id").asString();
    }

    public static RequestBuilder bookShipmentRequest(String quoteId, String idempotencyKey) {
        return post("/api/v1/shipments")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          "quoteId": "%s",
                          "shipper": {"name": "Erongo Marine Enterprises", "address": "Hafenstrasse 7, Walvis Bay", "contact": "shipping@erongomarine.na"},
                          "consignee": {"name": "Nordsee Coldstore GmbH", "address": "Fischmarkt 12, Hamburg", "contact": "ops@nordsee-coldstore.de"},
                          "payerAccountNumber": "%s",
                          "goodsDescription": "Chilled Atlantic kingklip fillets",
                          "declaredValue": 42000.00,
                          "declaredValueCurrency": "EUR"
                        }
                        """.formatted(quoteId, PAYER_ACCOUNT));
    }

    public JsonNode bookShipment() throws Exception {
        MvcResult res = mockMvc.perform(bookShipmentRequest(createQuote(), "fixture-" + UUID.randomUUID()))
                .andExpect(status().isCreated())
                .andReturn();
        return json(res);
    }

    public static RequestBuilder eventRequest(String shipmentId, String carrierEventId, String targetStatus,
                                              String occurredAt) {
        return eventRequest(shipmentId, carrierEventId, targetStatus, occurredAt, null);
    }

    public static RequestBuilder eventRequest(String shipmentId, String carrierEventId, String targetStatus,
                                              String occurredAt, String ifMatch) {
        var builder = post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {
                          %s
                          "eventType": "SCAN",
                          "targetStatus": "%s",
                          "location": "NAWVB",
                          "description": "Carrier scan %s",
                          "occurredAt": "%s"
                        }
                        """.formatted(carrierEventId == null ? "" : "\"carrierEventId\": \"" + carrierEventId + "\",",
                        targetStatus, targetStatus, occurredAt));
        if (ifMatch != null) {
            builder.header(HttpHeaders.IF_MATCH, ifMatch);
        }
        return builder;
    }

    /** Posts an event and returns the response body; fails unless the event was recorded (201). */
    public JsonNode recordEvent(String shipmentId, String carrierEventId, String targetStatus, String occurredAt)
            throws Exception {
        return json(mockMvc.perform(eventRequest(shipmentId, carrierEventId, targetStatus, occurredAt))
                .andExpect(status().isCreated())
                .andReturn());
    }

    public static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }
}
