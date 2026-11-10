package com.freightflow.logistics.shipment;

import com.freightflow.logistics.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TrackingEventIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    private static final String VALID_PAYER_ACCOUNT =
            ShipmentStateMachineUnitTest.buildValidAccountNumber("EUR", "000000002");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void handlesLifecycleProgressionOutOfOrderEventsDuplicateCarrierEventsAndPublicTracking() throws Exception {
        String quoteId = createQuote();

        MvcResult shipRes = mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", "track-ship-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "quoteId": "%s",
                                  "shipper": {
                                    "name": "Erongo Marine Enterprises",
                                    "address": "Hafenstrasse 7, Walvis Bay, Namibia",
                                    "contact": "shipping@erongomarine.na"
                                  },
                                  "consignee": {
                                    "name": "Nordsee Coldstore GmbH",
                                    "address": "St. Pauli Fischmarkt 12, 20359 Hamburg, Germany",
                                    "contact": "ops@nordsee-coldstore.de"
                                  },
                                  "payerAccountNumber": "%s",
                                  "goodsDescription": "Chilled Atlantic kingklip fillets",
                                  "declaredValue": 42000.00,
                                  "declaredValueCurrency": "EUR"
                                }
                                """.formatted(quoteId, VALID_PAYER_ACCOUNT)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""))
                .andReturn();

        JsonNode shipment = JSON.readTree(shipRes.getResponse().getContentAsString());
        String shipmentId = shipment.get("id").asString();
        String trackingNumber = shipment.get("trackingNumber").asString();

        // 1. AWAITING_PAYMENT -> BOOKED at NAWVB (2026-04-18T09:00:00Z)
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carrierEventId": "EVT-BOOKED-01",
                                  "eventType": "PAYMENT_CONFIRMED",
                                  "targetStatus": "BOOKED",
                                  "location": "NAWVB",
                                  "description": "Booking confirmed at Walvis Bay office",
                                  "occurredAt": "2026-04-18T09:00:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.appliedToStatus").value(true))
                .andExpect(jsonPath("$.duplicateIgnored").value(false))
                .andExpect(jsonPath("$.currentShipmentStatus").value("BOOKED"))
                .andExpect(jsonPath("$.locationTimezone").value("Africa/Windhoek"))
                .andExpect(jsonPath("$.occurredAtLocal").value("2026-04-18T11:00:00+02:00"));

        // 2. Duplicate carrierEventId ("EVT-BOOKED-01") -> 200 OK, duplicateIgnored = true, version stays 1
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carrierEventId": "EVT-BOOKED-01",
                                  "eventType": "PAYMENT_CONFIRMED",
                                  "targetStatus": "BOOKED",
                                  "location": "NAWVB",
                                  "description": "Booking confirmed at Walvis Bay office",
                                  "occurredAt": "2026-04-18T09:00:00Z"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.duplicateIgnored").value(true))
                .andExpect(jsonPath("$.shipmentVersion").value(1));

        // 3. Stale If-Match "0" on next event -> 412 precondition-failed
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                        .header(HttpHeaders.IF_MATCH, "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carrierEventId": "EVT-PICKUP-01",
                                  "eventType": "CARGO_PICKED_UP",
                                  "targetStatus": "PICKED_UP",
                                  "location": "NAWVB",
                                  "description": "Picked up from Erongo cold storage",
                                  "occurredAt": "2026-04-19T07:30:00Z"
                                }
                                """))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/precondition-failed"));

        // 4. BOOKED -> PICKED_UP -> AT_ORIGIN_PORT -> IN_TRANSIT
        postEvent(shipmentId, "EVT-PICKUP-01", "CARGO_PICKED_UP", "PICKED_UP", "NAWVB",
                "Picked up from Erongo cold storage", "2026-04-19T07:30:00Z");
        postEvent(shipmentId, "EVT-ORIGIN-PORT-01", "GATE_IN_ORIGIN", "AT_ORIGIN_PORT", "NAWVB",
                "Container gated in at Namport Walvis Bay terminal", "2026-04-20T06:00:00Z");
        postEvent(shipmentId, "EVT-DEPART-01", "VESSEL_DEPARTED", "IN_TRANSIT", "NAWVB",
                "Loaded on MV Dal Kalahari bound for Hamburg", "2026-04-22T10:00:00Z");

        // 5. Attempting to cancel once IN_TRANSIT -> 409 invalid-shipment-state-transition
        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\": \"Too late to cancel\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-shipment-state-transition"))
                .andExpect(jsonPath("$.currentStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.requestedStatus").value("CANCELLED"));

        // 6. Out-of-order older event (occurredAt 2026-04-20T08:30:00Z < latestEventAt 2026-04-22T10:00:00Z)
        // Stored in tracking history with appliedToStatus = false; status remains IN_TRANSIT!
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carrierEventId": "EVT-LATE-SCAN-01",
                                  "eventType": "TERMINAL_WEIGHBRIDGE",
                                  "targetStatus": "AT_ORIGIN_PORT",
                                  "location": "NAWVB",
                                  "description": "Late EDI scan from Walvis Bay weighbridge",
                                  "occurredAt": "2026-04-20T08:30:00Z"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedToStatus").value(false))
                .andExpect(jsonPath("$.currentShipmentStatus").value("IN_TRANSIT"));

        // 7. Invalid forward state transition (IN_TRANSIT -> DELIVERED skipping AT_DESTINATION_PORT) -> 409
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carrierEventId": "EVT-INVALID-JUMP",
                                  "eventType": "POD_SIGNED",
                                  "targetStatus": "DELIVERED",
                                  "location": "DEHAM",
                                  "description": "Premature delivery scan",
                                  "occurredAt": "2026-05-12T12:00:00Z"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-shipment-state-transition"))
                .andExpect(jsonPath("$.currentStatus").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.requestedStatus").value("DELIVERED"));

        // 8. Complete corridor arrival in Hamburg (DEHAM, Europe/Berlin CEST +02:00 in May)
        postEvent(shipmentId, "EVT-ARRIVE-DEHAM", "VESSEL_ARRIVED", "AT_DESTINATION_PORT", "DEHAM",
                "Discharged at HHLA Container Terminal Altenwerder, Hamburg", "2026-05-10T14:00:00Z");
        postEvent(shipmentId, "EVT-CUSTOMS-HOLD", "CUSTOMS_INSPECTION", "CUSTOMS_HOLD", "DEHAM",
                "Veterinary border inspection at Hamburg port", "2026-05-11T08:00:00Z");
        postEvent(shipmentId, "EVT-CUSTOMS-CLEARED", "CUSTOMS_RELEASED", "CUSTOMS_CLEARED", "DEHAM",
                "EU veterinary and import customs cleared", "2026-05-11T15:30:00Z");
        postEvent(shipmentId, "EVT-OUT-DELIVERY", "OUT_FOR_DELIVERY", "OUT_FOR_DELIVERY", "DEHAM",
                "Dispatched on reefer truck to St. Pauli Fischmarkt", "2026-05-12T06:00:00Z");
        postEvent(shipmentId, "EVT-DELIVERED", "DELIVERED", "DELIVERED", "DEHAM",
                "Delivered and signed by Nordsee Coldstore GmbH", "2026-05-12T09:15:00Z");

        // 9. Public tracking endpoint GET /api/v1/shipments/track/{trackingNumber}
        MvcResult publicRes = mockMvc.perform(get("/api/v1/shipments/track/{trackingNumber}", trackingNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value(trackingNumber))
                .andExpect(jsonPath("$.origin.unlocode").value("NAWVB"))
                .andExpect(jsonPath("$.origin.timezone").value("Africa/Windhoek"))
                .andExpect(jsonPath("$.destination.unlocode").value("DEHAM"))
                .andExpect(jsonPath("$.destination.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.status").value("DELIVERED"))
                .andExpect(jsonPath("$.events.length()").value(10))
                // Verify customer PII is completely absent from public tracking response
                .andExpect(jsonPath("$.shipper").doesNotExist())
                .andExpect(jsonPath("$.consignee").doesNotExist())
                .andExpect(jsonPath("$.payerAccountNumber").doesNotExist())
                .andExpect(jsonPath("$.declaredValue").doesNotExist())
                .andExpect(jsonPath("$.quotedTotalAmount").doesNotExist())
                .andReturn();

        JsonNode publicJson = JSON.readTree(publicRes.getResponse().getContentAsString());
        JsonNode events = publicJson.get("events");
        // Verify chronological order (index 3 is 2026-04-20T08:30:00Z out-of-order weighbridge scan with appliedToStatus=false)
        assertThat(events.get(3).get("eventType").asString()).isEqualTo("TERMINAL_WEIGHBRIDGE");
        assertThat(events.get(3).get("appliedToStatus").asBoolean()).isFalse();
        // Verify final Hamburg delivery event has Europe/Berlin local offset (+02:00 in May)
        assertThat(events.get(9).get("location").asString()).isEqualTo("DEHAM");
        assertThat(events.get(9).get("locationTimezone").asString()).isEqualTo("Europe/Berlin");
        assertThat(events.get(9).get("occurredAtLocal").asString()).isEqualTo("2026-05-12T11:15:00+02:00");

        // 10. Typo in tracking number check character -> 400 invalid-tracking-number
        char lastChar = trackingNumber.charAt(trackingNumber.length() - 1);
        String typoTracking = trackingNumber.substring(0, trackingNumber.length() - 1)
                + (lastChar == 'A' ? 'B' : 'A');
        mockMvc.perform(get("/api/v1/shipments/track/{trackingNumber}", typoTracking))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-tracking-number"));

        // 11. Valid check character for non-existent tracking number -> 404 shipment-not-found
        java.util.concurrent.atomic.AtomicLong unissuedSeq =
                new java.util.concurrent.atomic.AtomicLong(999_999_999L);
        String validButUnissued = TrackingNumber.generate(unissuedSeq::getAndIncrement);
        mockMvc.perform(get("/api/v1/shipments/track/{trackingNumber}", validButUnissued))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/shipment-not-found"));
    }

    private void postEvent(
            String shipmentId,
            String carrierEventId,
            String eventType,
            String targetStatus,
            String location,
            String description,
            String occurredAt) throws Exception {
        mockMvc.perform(post("/api/v1/shipments/{id}/tracking-events", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "carrierEventId": "%s",
                                  "eventType": "%s",
                                  "targetStatus": "%s",
                                  "location": "%s",
                                  "description": "%s",
                                  "occurredAt": "%s"
                                }
                                """.formatted(carrierEventId, eventType, targetStatus, location, description, occurredAt)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.appliedToStatus").value(true))
                .andExpect(jsonPath("$.currentShipmentStatus").value(targetStatus));
    }

    private String createQuote() throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/quotes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "origin": "NAWVB",
                                  "destination": "DEHAM",
                                  "mode": "SEA_LCL",
                                  "incoterm": "FCA",
                                  "payerRole": "CONSIGNEE",
                                  "packages": [
                                    {
                                      "lengthCm": 120,
                                      "widthCm": 100,
                                      "heightCm": 150,
                                      "weightKg": 500,
                                      "quantity": 2
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(res.getResponse().getContentAsString()).get("id").asString();
    }
}
