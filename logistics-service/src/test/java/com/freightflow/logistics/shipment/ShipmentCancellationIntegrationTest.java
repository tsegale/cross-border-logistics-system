package com.freightflow.logistics.shipment;

import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShipmentCancellationIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String shipmentId;

    @BeforeEach
    void shipment() throws Exception {
        shipmentId = new LogisticsFixtures(mockMvc).bookShipment().get("id").asString();
    }

    @Test
    void fetchedShipmentCarriesItsVersionAsETag() throws Exception {
        mockMvc.perform(get("/api/v1/shipments/{id}", shipmentId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"0\""));
    }

    @Test
    void cancellationWithTheCurrentETagSucceeds() throws Exception {
        mockMvc.perform(cancel("\"0\""))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ETAG, "\"1\""))
                .andExpect(jsonPath("$.status").value("CANCELLED"))
                .andExpect(jsonPath("$.cancellationReason").value("Customer postponed order"));
    }

    @Test
    void cancellationWithAStaleETagIs412() throws Exception {
        mockMvc.perform(cancel("\"99\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/precondition-failed"));
    }

    @Test
    void cancellingTwiceIsAnInvalidTransition() throws Exception {
        mockMvc.perform(cancel("\"0\"")).andExpect(status().isOk());

        mockMvc.perform(cancel("\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-shipment-state-transition"))
                .andExpect(jsonPath("$.currentStatus").value("CANCELLED"));
    }

    private RequestBuilder cancel(String ifMatch) {
        return post("/api/v1/shipments/{id}/cancellation", shipmentId)
                .header(HttpHeaders.IF_MATCH, ifMatch)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Customer postponed order\"}");
    }
}
