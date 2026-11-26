package com.freightflow.notification.events;

import com.freightflow.notification.security.Caller;
import com.freightflow.notification.support.Events;
import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.Mailpit;
import com.freightflow.notification.support.TestCallers;
import com.freightflow.notification.support.TestSpans;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.freightflow.notification.support.Events.await;
import static com.freightflow.notification.support.Events.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A retried event stays in its trace (ADR 0030). The hold's release arrives before notification has seen the
 * shipment, so its first attempt fails and it goes to the first retry topic. The shipment's booking arrives
 * meanwhile, the retry succeeds, and the release email is sent in the trace the hold event carried, not in a new one
 * that starts at the retry topic. Retries are 2 s apart here (the other tests use 200 ms), so the booking always lands
 * between the first attempt and the first retry.
 */
@TestPropertySource(properties = {"freightflow.events.consumer.retry-delay=2000",
        "freightflow.events.consumer.retry-multiplier=2", "freightflow.events.consumer.retry-max-delay=8000"})
class RetryTraceIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void anEventHandledOnARetryTopicStaysInItsTrace() throws Exception {
        UUID organization = UUID.randomUUID();
        String recipient = "retry-" + organization.toString().substring(0, 8) + "@nordsee-coldstore.example";
        settings(organization, recipient);
        UUID shipment = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString().replace("-", "");

        Events.publish("banking", EventTopics.HOLD, hold.toString(), Events.event("hold.released", event -> {
            event.put("aggregateId", hold.toString());
            ObjectNode h = (ObjectNode) payload(event).path("hold");
            h.put("holdId", hold.toString());
            h.put("organizationId", organization.toString());
            h.put("reference", "SHIPMENT " + shipment);
        }), Map.of("traceparent", "00-" + traceId + "-a3ce929d0e0e4736-01"));
        // The first attempt, within a second, fails: the shipment is not known yet. The booking arrives before the
        // first retry, 2 s after it.
        Thread.sleep(800);
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), Events.event("shipment.booked", event -> {
            event.put("aggregateId", shipment.toString());
            payload(event).put("shipmentId", shipment.toString());
            payload(event).put("trackingNumber", "FF-000000C5-1");
            payload(event).put("organizationId", organization.toString());
            payload(event).put("payerOrganizationId", organization.toString());
        }));

        await(60, () -> Mailpit.to(recipient).stream()
                .filter(message -> message.subject().startsWith("Reservation released")).findFirst().orElse(null));
        // Spans are exported as they end, the consumers' possibly after the send: wait for all three.
        List<SpanData> spans = TestSpans.trace(traceId, found -> TestSpans.names(found).contains("email send")
                && found.stream().filter(span -> span.getKind() == SpanKind.CONSUMER).count() >= 2);
        assertThat(spans).filteredOn(span -> span.getName().equals("email send")).singleElement()
                .satisfies(span -> assertThat(span.getAttributes().asMap().toString())
                        .contains("freightflow.notification.kind=payment-released"));
        assertThat(spans).as("the consumer spans of the main topic and of its retry topic, in the same trace")
                .filteredOn(span -> span.getKind() == SpanKind.CONSUMER).hasSizeGreaterThanOrEqualTo(2);
        assertThat(spans).extracting(span -> span.getAttributes().asMap().toString())
                .anySatisfy(attributes -> assertThat(attributes).contains(EventTopics.RETRY_SUFFIX));
    }

    private void settings(UUID organization, String recipient) throws Exception {
        Caller customer = new Caller("customer-" + organization, "freightflow-web", organization,
                Set.of(Caller.CUSTOMER), Set.of());
        mockMvc.perform(put("/api/v1/notification-settings").with(TestCallers.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"language":"en","recipients":["%s"],"kinds":["payment-released"]}
                                """.formatted(recipient)))
                .andExpect(status().isOk());
    }
}
