package com.freightflow.notification.deadletter;

import com.freightflow.notification.events.EventTopics;
import com.freightflow.notification.security.Caller;
import com.freightflow.notification.support.Events;
import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.Mailpit;
import com.freightflow.notification.support.TestCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.node.ObjectNode;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.freightflow.notification.support.Events.await;
import static com.freightflow.notification.support.Events.payload;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Retry topics and dead letters (ADR 0026): an event that keeps failing goes through the retry topics to the
 * dead-letter topic and into {@code dead_letters}; a poison message goes there at once; an operator redrives a dead
 * letter once its cause is fixed, and is recorded as the one who did.
 */
class DeadLetterIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aHoldEventForAnUnknownShipmentIsRetriedThenDeadLetteredAndRedrivenOnceTheShipmentIsKnown() throws Exception {
        UUID org = UUID.randomUUID();
        String recipient = "billing-" + org.toString().substring(0, 8) + "@hanseatic.example";
        saveSettings(org, recipient);
        UUID shipment = UUID.randomUUID();
        UUID hold = UUID.randomUUID();
        String released = Events.event("hold.released", event -> {
            event.put("aggregateId", hold.toString());
            ObjectNode h = (ObjectNode) payload(event).path("hold");
            h.put("holdId", hold.toString());
            h.put("organizationId", org.toString());
            h.put("reference", "SHIPMENT " + shipment);
        });
        UUID eventId = UUID.fromString(tools.jackson.databind.json.JsonMapper.builder().build().readTree(released)
                .path("eventId").asString());

        Events.publish("banking", EventTopics.HOLD, hold.toString(), released);
        // Three retries, 200 ms then doubling (IntegrationTest), then the dead-letter topic.
        Map<String, Object> dead = await(60, () -> jdbcTemplate.queryForList(
                "SELECT * FROM dead_letters WHERE event_id = ?", eventId).stream().findFirst().orElse(null));
        assertThat(dead.get("original_topic")).isEqualTo(EventTopics.HOLD);
        assertThat(dead.get("topic")).isEqualTo(EventTopics.HOLD + EventTopics.DLT_SUFFIX);
        assertThat((String) dead.get("error")).contains("is not known yet");
        assertThat(dead.get("status")).isEqualTo("NEW");
        UUID id = (UUID) dead.get("id");

        mockMvc.perform(get("/api/v1/dead-letters").param("status", "NEW").with(TestCallers.as(TestCallers.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].eventType", id).value("hold.released"));
        // Still unknown: the redrive fails, is counted, and the letter stays.
        mockMvc.perform(post("/api/v1/dead-letters/{id}/redrive", id).with(TestCallers.as(TestCallers.OPERATOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/dead-letter-redrive-failed"))
                .andExpect(jsonPath("$.redriveAttempts").value(1));

        // The booking arrives; now the redrive goes through and the email is sent.
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), Events.event("shipment.booked",
                event -> {
                    event.put("aggregateId", shipment.toString());
                    payload(event).put("shipmentId", shipment.toString());
                    payload(event).put("trackingNumber", "FF-00000092-6");
                    payload(event).put("organizationId", org.toString());
                    payload(event).put("payerOrganizationId", org.toString());
                }));
        await(30, () -> jdbcTemplate.queryForObject("SELECT count(*) FROM shipments WHERE shipment_id = ?",
                Integer.class, shipment) == 1);

        mockMvc.perform(post("/api/v1/dead-letters/{id}/redrive", id).with(TestCallers.as(TestCallers.OPERATOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REDRIVEN"))
                .andExpect(jsonPath("$.redrivenBy").value(TestCallers.OPERATOR.subject()))
                .andExpect(jsonPath("$.redriveAttempts").value(2));
        await(30, () -> Mailpit.to(recipient).stream()
                .anyMatch(message -> message.subject().equals("Reservation released: FF-00000092-6")));

        mockMvc.perform(post("/api/v1/dead-letters/{id}/redrive", id).with(TestCallers.as(TestCallers.OPERATOR)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/dead-letter-already-redriven"));
    }

    @Test
    void aPoisonMessageIsDeadLetteredWithoutRetries() throws Exception {
        String key = "poison-" + UUID.randomUUID();
        Events.publish("logistics", EventTopics.SHIPMENT, key, "{not an event");

        Map<String, Object> dead = await(30, () -> jdbcTemplate.queryForList(
                "SELECT * FROM dead_letters WHERE record_key = ?", key).stream().findFirst().orElse(null));
        assertThat(dead.get("record_value")).isEqualTo("{not an event");
        assertThat((String) dead.get("error")).contains("Not a readable event");
        assertThat(dead.get("event_id")).isNull();
    }

    @Test
    void anUnknownDeadLetterIs404() throws Exception {
        mockMvc.perform(post("/api/v1/dead-letters/{id}/redrive", UUID.randomUUID())
                        .with(TestCallers.as(TestCallers.OPERATOR)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/dead-letter-not-found"));
    }

    private void saveSettings(UUID org, String recipient) throws Exception {
        Caller customer = new Caller("customer-" + org, "freightflow-web", org, Set.of(Caller.CUSTOMER), Set.of());
        mockMvc.perform(put("/api/v1/notification-settings").with(TestCallers.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"language":"en","recipients":["%s"],"kinds":["payment-released"]}
                                """.formatted(recipient)))
                .andExpect(status().isOk());
    }
}
