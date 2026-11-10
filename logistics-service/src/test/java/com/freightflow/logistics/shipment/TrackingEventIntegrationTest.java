package com.freightflow.logistics.shipment;

import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static com.freightflow.logistics.support.LogisticsFixtures.eventRequest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Recording tracking events and reading the public tracking view, one behaviour per test. */
class TrackingEventIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private LogisticsFixtures fixtures;
    private String shipmentId;
    private String trackingNumber;

    @BeforeEach
    void shipment() throws Exception {
        fixtures = new LogisticsFixtures(mockMvc);
        JsonNode shipment = fixtures.bookShipment();
        shipmentId = shipment.get("id").asString();
        trackingNumber = shipment.get("trackingNumber").asString();
    }

    @Test
    void appliedEventBumpsTheETagAndShowsTheLocationsLocalTime() throws Exception {
        mockMvc.perform(eventRequest(shipmentId, "EVT-BOOKED-01", "BOOKED", "2026-04-18T09:00:00Z", "\"0\""))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.appliedToStatus").value(true))
                .andExpect(jsonPath("$.duplicateIgnored").value(false))
                .andExpect(jsonPath("$.currentShipmentStatus").value("BOOKED"))
                .andExpect(jsonPath("$.locationTimezone").value("Africa/Windhoek"))
                .andExpect(jsonPath("$.occurredAtLocal").value("2026-04-18T11:00:00+02:00"));
    }

    @Test
    void resentCarrierEventIsIgnoredWithoutChangingTheVersion() throws Exception {
        fixtures.recordEvent(shipmentId, "EVT-BOOKED-01", "BOOKED", "2026-04-18T09:00:00Z");

        mockMvc.perform(eventRequest(shipmentId, "EVT-BOOKED-01", "BOOKED", "2026-04-18T09:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.duplicateIgnored").value(true))
                .andExpect(jsonPath("$.shipmentVersion").value(1));
    }

    @Test
    void cancellationAfterPickupIsAnInvalidTransition() throws Exception {
        fixtures.recordEvent(shipmentId, "E1", "BOOKED", "2026-04-18T09:00:00Z");
        fixtures.recordEvent(shipmentId, "E2", "PICKED_UP", "2026-04-19T08:00:00Z");

        mockMvc.perform(post("/api/v1/shipments/{id}/cancellation", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Customer changed their mind\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-shipment-state-transition"))
                .andExpect(jsonPath("$.currentStatus").value("PICKED_UP"))
                .andExpect(jsonPath("$.requestedStatus").value("CANCELLED"));
    }

    @Test
    void publicTrackingListsEventsChronologicallyInLocalTimeWithoutCustomerData() throws Exception {
        fixtures.recordEvent(shipmentId, "E1", "BOOKED", "2026-04-18T09:00:00Z");
        fixtures.recordEvent(shipmentId, "E2", "IN_TRANSIT", "2026-04-22T10:00:00Z");
        fixtures.recordEvent(shipmentId, "E-LATE", "AT_ORIGIN_PORT", "2026-04-20T08:30:00Z");

        mockMvc.perform(get("/api/v1/shipments/track/{trackingNumber}", trackingNumber))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackingNumber").value(trackingNumber))
                .andExpect(jsonPath("$.origin.timezone").value("Africa/Windhoek"))
                .andExpect(jsonPath("$.destination.timezone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.status").value("IN_TRANSIT"))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[1].occurredAtUtc").value("2026-04-20T08:30:00Z"))
                .andExpect(jsonPath("$.events[1].appliedToStatus").value(false))
                .andExpect(jsonPath("$.shipper").doesNotExist())
                .andExpect(jsonPath("$.consignee").doesNotExist())
                .andExpect(jsonPath("$.payerAccountNumber").doesNotExist())
                .andExpect(jsonPath("$.declaredValue").doesNotExist())
                .andExpect(jsonPath("$.quotedTotalAmount").doesNotExist());
    }

    @Test
    void mistypedTrackingNumberIs400NotA404() throws Exception {
        char check = trackingNumber.charAt(12);
        char wrong = TrackingNumber.ALPHABET.charAt((TrackingNumber.ALPHABET.indexOf(check) + 1) % 32);
        mockMvc.perform(get("/api/v1/shipments/track/{trackingNumber}", trackingNumber.substring(0, 12) + wrong))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-tracking-number"));
    }

    @Test
    void validButUnissuedTrackingNumberIs404() throws Exception {
        AtomicLong unissued = new AtomicLong(999_999_999L);
        mockMvc.perform(get("/api/v1/shipments/track/{trackingNumber}", TrackingNumber.generate(unissued::getAndIncrement)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/shipment-not-found"));
    }

    @Test
    void eventForAnUnknownShipmentIs404() throws Exception {
        mockMvc.perform(eventRequest(UUID.randomUUID().toString(), "E1", "BOOKED", "2026-04-18T09:00:00Z"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/shipment-not-found"));
    }
}
