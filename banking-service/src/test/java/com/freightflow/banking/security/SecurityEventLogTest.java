package com.freightflow.banking.security;

import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A token banking rejects leaves a security event line (ADR 0017), with the request id it came with. The line was once
 * dropped for every rejection (a requestId logged twice made the encoder throw) and nothing failed; LogbackErrors now
 * fails such a test, and this one checks the line itself.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecurityEventLogTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aRejectedTokenIsLoggedAsASecurityEventWithItsRequestId(CapturedOutput output) throws Exception {
        mockMvc.perform(get("/api/v1/accounts/FF14EUR000000001")
                        .header("Authorization", "Bearer not-a-token")
                        .header("X-Request-Id", "security-event-check-1"))
                .andExpect(status().isUnauthorized());

        assertThat(output.getOut().lines()
                .filter(line -> line.contains("\"authentication-failed\""))
                .filter(line -> line.contains("security-event-check-1")))
                .as("a security event line with the request id").hasSize(1);
    }
}
