package com.freightflow.banking.telemetry;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.events.EventRelay;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.TelemetryLeaks;
import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.support.TestSpans;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The hold logistics' payment relay asks for joins logistics' trace (ADR 0030): the request's traceparent reaches the
 * hold's event row, and banking's relay publishes the event in that trace. The spans name the hold, never a bearer
 * token (ADR 0024).
 */
@ExtendWith(OutputCaptureExtension.class)
class HoldTraceIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private EventRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aHoldAuthorisedInLogisticsTraceIsPublishedInIt(CapturedOutput output) throws Exception {
        AccountResponse payer = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("EUR", TestCallers.NAMIB));
        funding.deposit(payer.id().toString(), new BigDecimal("1000.00"), "EUR", "SEED");
        String traceId = UUID.randomUUID().toString().replace("-", "");

        String body = mockMvc.perform(post("/api/v1/holds").with(TestCallers.logistics())
                        .header("Idempotency-Key", "trace-" + UUID.randomUUID())
                        .header("traceparent", "00-" + traceId + "-b7ad6b7169203331-01")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerOrganizationId":"%s","payerAccount":"%s","amount":100.00,"currency":"EUR","reference":"TRACE"}
                                """.formatted(TestCallers.NAMIB, payer.accountNumber())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID hold = UUID.fromString(JSON.readTree(body).get("id").asString());

        assertThat(jdbcTemplate.queryForObject("SELECT traceparent FROM event_outbox WHERE aggregate_id = ? "
                + "AND event_type = 'hold.authorised'", String.class, hold)).contains(traceId);

        // Test classes share this database and run in random order: another may have left more unpublished events
        // than one relay batch claims, so relay until this hold's event is out or nothing is left to claim.
        for (int round = 0; round < 100 && unpublished(hold) > 0; round++) {
            if (relay.relayOnce() == 0) {
                break;
            }
        }
        assertThat(unpublished(hold)).as("the hold's event was relayed").isZero();

        List<SpanData> spans = TestSpans.trace(traceId, found -> TestSpans.names(found).contains("event-outbox relay"));
        assertThat(spans).filteredOn(span -> span.getName().equals("http post /api/v1/holds")).singleElement()
                .satisfies(span -> assertThat(span.getAttributes().asMap().toString())
                        .contains("freightflow.hold.id=" + hold, "freightflow.request_id="));
        assertThat(spans).filteredOn(span -> span.getName().equals("event-outbox relay")).isNotEmpty()
                .anySatisfy(span -> assertThat(span.getAttributes().asMap().toString())
                        .contains("freightflow.event.type=hold.authorised"));
        assertThat(TelemetryLeaks.in(spans)).isEmpty();
        assertThat(TelemetryLeaks.in(output.getAll())).isEmpty();
    }

    private int unpublished(UUID hold) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM event_outbox WHERE aggregate_id = ? AND published_at IS NULL", Integer.class, hold);
        return count == null ? 0 : count;
    }
}
