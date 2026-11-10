package com.freightflow.logistics.shipment;

import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ShipmentListingIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void keysetPagesVisitEveryShipmentOnceNewestFirst() throws Exception {
        LogisticsFixtures fixtures = new LogisticsFixtures(mockMvc);
        for (int i = 0; i < 3; i++) {
            fixtures.bookShipment();
        }

        Set<String> seen = new HashSet<>();
        String cursor = null;
        Instant previous = null;
        do {
            MockHttpServletRequestBuilder request = get("/api/v1/shipments")
                    .param("size", "2").param("status", "AWAITING_PAYMENT");
            if (cursor != null) {
                request.param("cursor", cursor);
            }
            JsonNode page = json(mockMvc.perform(request).andExpect(status().isOk()).andReturn());
            for (JsonNode item : page.get("items")) {
                assertThat(seen.add(item.get("id").asString())).isTrue();
                assertThat(item.get("status").asString()).isEqualTo("AWAITING_PAYMENT");
                Instant createdAt = Instant.parse(item.get("createdAt").asString());
                if (previous != null) {
                    assertThat(createdAt).isBeforeOrEqualTo(previous);
                }
                previous = createdAt;
            }
            JsonNode next = page.get("nextCursor");
            cursor = next == null || next.isNull() ? null : next.asString();
        } while (cursor != null);
        assertThat(seen).hasSizeGreaterThanOrEqualTo(3);
    }

    @Test
    void pageSizeOutOfRangeIs400() throws Exception {
        mockMvc.perform(get("/api/v1/shipments").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-cursor"));
    }
}
