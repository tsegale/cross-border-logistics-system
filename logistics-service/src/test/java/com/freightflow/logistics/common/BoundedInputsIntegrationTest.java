package com.freightflow.logistics.common;

import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.TestCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A document past the JSON limits is refused while it is parsed, with a problem that names no parser detail, and a
 * carrier's notification past the webhook's limit before its signature is computed (ADR 0042).
 */
class BoundedInputsIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aDeeplyNestedDocumentIsA400ThatSaysNothingOfTheParser() throws Exception {
        String nested = "{\"a\":".repeat(40) + "1" + "}".repeat(40);
        mockMvc.perform(post("/api/v1/quotes").with(TestCallers.as(TestCallers.CUSTOMER_NAMIB))
                        .contentType(MediaType.APPLICATION_JSON).content(nested))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-request-body"))
                .andExpect(content().string(not(containsString("nesting"))))
                .andExpect(content().string(not(containsString("jackson"))));
    }

    @Test
    void aNumberLongerThanAnyAmountIsA400() throws Exception {
        String body = "{\"origin\":\"NAWVB\",\"packages\":[{\"weightKg\":" + "9".repeat(100) + "}]}";
        mockMvc.perform(post("/api/v1/quotes").with(TestCallers.as(TestCallers.CUSTOMER_NAMIB))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-request-body"));
    }

    @Test
    void aNotificationOverTheWebhookLimitIsA413BeforeItsSignatureIsChecked() throws Exception {
        byte[] big = new byte[256 * 1024 + 1];
        java.util.Arrays.fill(big, (byte) ' ');
        mockMvc.perform(post("/api/v1/dcsa/notifications/carrier-kalahari-freight")
                        .contentType(MediaType.APPLICATION_JSON).content(big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/request-too-large"));
    }
}
