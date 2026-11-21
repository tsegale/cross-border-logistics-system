package com.freightflow.notification.notify;

import com.freightflow.notification.events.EventTopics;
import com.freightflow.notification.security.Caller;
import com.freightflow.notification.support.Events;
import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.Mailpit;
import com.freightflow.notification.support.TelemetryLeaks;
import com.freightflow.notification.support.TestCallers;
import com.freightflow.notification.support.TestSpans;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

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
 * From event to inbox (ADR 0027): logistics' and banking's events, published to the real topics as their producers,
 * become emails in the organisation's language that arrive at Mailpit, once per event and recipient.
 */
@ExtendWith(OutputCaptureExtension.class)
class NotificationFlowIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The email is part of the booking's trace (ADR 0030): Kafka's consumer span continues the record's traceparent,
     * the notification row keeps it, and the send is a span of the same trace. The recipient is in neither the spans
     * nor the logs (ADR 0024).
     */
    @Test
    void theEmailIsSentInTheTraceOfTheEventAndTheRecipientStaysOutOfTelemetry(CapturedOutput output) throws Exception {
        Org org = org("en");
        UUID shipment = UUID.randomUUID();
        String traceId = UUID.randomUUID().toString().replace("-", "");
        String booked = booked(org, shipment, "FF-000000B4-8");

        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), booked,
                Map.of("traceparent", "00-" + traceId + "-00f067aa0ba902b7-01"));

        await(30, () -> first(org.recipient()));
        assertThat(jdbcTemplate.queryForObject("SELECT traceparent FROM notifications WHERE shipment_id = ?",
                String.class, shipment)).contains(traceId);
        List<SpanData> spans = TestSpans.trace(traceId, found -> TestSpans.names(found).contains("email send"));
        assertThat(spans).extracting(SpanData::getKind).contains(SpanKind.CONSUMER);
        assertThat(spans).filteredOn(span -> span.getName().equals("email send")).singleElement()
                .satisfies(span -> assertThat(span.getAttributes().asMap().toString())
                        .contains("freightflow.notification.kind=shipment-booked", "freightflow.shipment.id=" + shipment)
                        .doesNotContain(org.recipient()));
        assertThat(TelemetryLeaks.in(spans)).isEmpty();
        assertThat(TelemetryLeaks.in(output.getAll())).isEmpty();
    }

    @Test
    void aGermanOrganisationGetsSendungGebuchtFormattedForGermany() throws Exception {
        Org org = org("de");
        UUID shipment = UUID.randomUUID();

        publishBooked(org, shipment, "FF-0000004D-K");

        Mailpit.Message email = await(30, () -> first(org.recipient()));
        assertThat(email.subject()).isEqualTo("Sendung gebucht: FF-0000004D-K");
        assertThat(email.text()).contains("Ihre Sendung FF-0000004D-K von NAWVB nach DEHAM ist gebucht")
                .contains("Betrag: 1.840,50 €")
                .contains("Geplante Abholung: 25.11.2026");
        assertThat(email.html()).contains("lang=\"de\"").contains("1.840,50 €");
        assertThat(email.messageId()).endsWith("@notifications.freightflow.dev");
    }

    @Test
    void anEnglishOrganisationGetsItInEnglish() throws Exception {
        Org org = org("en");
        publishBooked(org, UUID.randomUUID(), "FF-0000005E-2");

        Mailpit.Message email = await(30, () -> first(org.recipient()));
        assertThat(email.subject()).isEqualTo("Shipment booked: FF-0000005E-2");
        assertThat(email.text()).contains("Amount: €1,840.50").contains("Planned pickup: 25 Nov 2026");
    }

    @Test
    void aReplayedEventSendsNoSecondEmail() throws Exception {
        Org org = org("de");
        UUID shipment = UUID.randomUUID();
        String booked = booked(org, shipment, "FF-0000006F-3");

        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), booked);
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), booked);
        // Same key, same partition: once this later event has been handled, both copies before it were.
        UUID marker = Events.uuidV7();
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), statusChanged(org, shipment,
                "FF-0000006F-3", "IN_TRANSIT", 2, marker));
        await(30, () -> processed(marker));
        await(30, () -> first(org.recipient()));

        assertThat(Mailpit.to(org.recipient())).hasSize(1);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE organization_id = ?",
                Integer.class, org.id())).isEqualTo(1);
    }

    @Test
    void aReleasedHoldSendsTheReleaseEmailWithTheShipmentsTrackingNumber() throws Exception {
        Org org = org("de");
        UUID shipment = UUID.randomUUID();
        publishBooked(org, shipment, "FF-00000070-4");
        await(30, () -> first(org.recipient()));

        UUID hold = UUID.randomUUID();
        Events.publish("banking", EventTopics.HOLD, hold.toString(), Events.event("hold.released", event -> {
            event.put("aggregateId", hold.toString());
            var h = (tools.jackson.databind.node.ObjectNode) payload(event).path("hold");
            h.put("holdId", hold.toString());
            h.put("organizationId", org.id().toString());
            h.put("reference", "SHIPMENT " + shipment);
        }));

        Mailpit.Message released = await(30, () -> Mailpit.to(org.recipient()).stream()
                .filter(message -> message.subject().startsWith("Reservierung freigegeben")).findFirst()
                .orElse(null));
        assertThat(released.subject()).isEqualTo("Reservierung freigegeben: FF-00000070-4");
        assertThat(released.text()).contains("Die Reservierung über 1.840,50 €");
    }

    @Test
    void anOlderStatusThatArrivesLateIsDroppedAsStale() throws Exception {
        Org org = org("en");
        UUID shipment = UUID.randomUUID();
        publishBooked(org, shipment, "FF-00000081-5");
        UUID delivered = Events.uuidV7();
        UUID late = Events.uuidV7();
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(),
                statusChanged(org, shipment, "FF-00000081-5", "DELIVERED", 5, delivered));
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(),
                statusChanged(org, shipment, "FF-00000081-5", "PICKED_UP", 3, late));
        await(30, () -> processed(late));
        await(30, () -> Mailpit.to(org.recipient()).size() == 2);

        assertThat(Mailpit.to(org.recipient())).extracting(Mailpit.Message::subject)
                .containsExactlyInAnyOrder("Shipment booked: FF-00000081-5", "Shipment FF-00000081-5: Delivered");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM shipments WHERE shipment_id = ?", String.class,
                shipment)).isEqualTo("DELIVERED");
    }

    @Test
    void nothingIsSentToAnOrganisationWithoutRecipientsOrThatTurnedTheKindOff() throws Exception {
        Org org = org("en");
        mockMvc.perform(put("/api/v1/notification-settings").with(TestCallers.as(org.caller()))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"language":"en","recipients":["%s"],"kinds":["payment-failed"]}
                                """.formatted(org.recipient())))
                .andExpect(status().isOk());
        UUID marker = Events.uuidV7();
        UUID shipment = UUID.randomUUID();
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), Events.event("shipment.booked",
                event -> {
                    event.put("eventId", marker.toString());
                    event.put("aggregateId", shipment.toString());
                    payload(event).put("shipmentId", shipment.toString());
                    payload(event).put("organizationId", org.id().toString());
                }));
        await(30, () -> processed(marker));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?",
                Integer.class, marker)).isZero();
    }

    @Test
    void paymentFailuresAndCapturesAreEmailedAndEventsWithoutAnEmailAreOnlyRecorded() throws Exception {
        Org org = org("de");
        UUID shipment = UUID.randomUUID();
        publishBooked(org, shipment, "FF-000000A3-7");
        await(30, () -> first(org.recipient()));

        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), Events.event("shipment.payment-failed",
                event -> {
                    event.put("aggregateId", shipment.toString());
                    event.put("sequence", 2);
                    payload(event).put("shipmentId", shipment.toString());
                    payload(event).put("trackingNumber", "FF-000000A3-7");
                    payload(event).put("organizationId", org.id().toString());
                    payload(event).put("payerOrganizationId", org.id().toString());
                }));
        UUID hold = UUID.randomUUID();
        Events.publish("banking", EventTopics.HOLD, hold.toString(), Events.event("hold.captured", event -> {
            event.put("aggregateId", hold.toString());
            var h = (tools.jackson.databind.node.ObjectNode) payload(event).path("hold");
            h.put("holdId", hold.toString());
            h.put("organizationId", org.id().toString());
            h.put("reference", "SHIPMENT " + shipment);
        }));
        // No email: a status between milestones, a hold that is not a shipment's, an authorisation, a tracking event.
        UUID inTransit = Events.uuidV7();
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(),
                statusChanged(org, shipment, "FF-000000A3-7", "IN_TRANSIT", 3, inTransit));
        UUID foreignHold = Events.uuidV7();
        Events.publish("banking", EventTopics.HOLD, hold.toString(), Events.event("hold.released", event -> {
            event.put("eventId", foreignHold.toString());
            ((tools.jackson.databind.node.ObjectNode) payload(event).path("hold")).put("reference", "INVOICE 42");
        }));
        UUID authorised = Events.uuidV7();
        Events.publish("banking", EventTopics.HOLD, hold.toString(), Events.event("hold.authorised",
                event -> event.put("eventId", authorised.toString())));
        UUID tracking = Events.uuidV7();
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), Events.event("tracking-event.recorded",
                event -> event.put("eventId", tracking.toString())));

        await(30, () -> Mailpit.to(org.recipient()).size() == 3);
        assertThat(Mailpit.to(org.recipient())).extracting(Mailpit.Message::subject).containsExactlyInAnyOrder(
                "Sendung gebucht: FF-000000A3-7", "Zahlung fehlgeschlagen: FF-000000A3-7",
                "Betrag belastet: FF-000000A3-7");
        assertThat(Mailpit.to(org.recipient()).stream().filter(m -> m.subject().startsWith("Zahlung")).findFirst()
                .orElseThrow().text()).contains("nicht genügend Guthaben auf dem Zahlungskonto");
        for (UUID id : List.of(inTransit, foreignHold, authorised, tracking)) {
            await(30, () -> processed(id));
        }
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE organization_id = ?",
                Integer.class, org.id())).isEqualTo(3);
    }

    @Test
    void anOrganisationThatNeverSavedSettingsGetsNothing() {
        UUID marker = Events.uuidV7();
        UUID shipment = UUID.randomUUID();
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), Events.event("shipment.booked",
                event -> {
                    event.put("eventId", marker.toString());
                    event.put("aggregateId", shipment.toString());
                    payload(event).put("shipmentId", shipment.toString());
                    payload(event).put("organizationId", UUID.randomUUID().toString());
                }));
        await(30, () -> processed(marker));

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM notifications WHERE event_id = ?",
                Integer.class, marker)).isZero();
    }

    // ------------------------------------------------------------------ helpers

    record Org(UUID id, String recipient, Caller caller) {
    }

    /** A fresh organisation with one recipient, whose customer saved its settings in {@code language}. */
    private Org org(String language) throws Exception {
        UUID id = UUID.randomUUID();
        String recipient = "ops-" + id.toString().substring(0, 8) + "@nordsee-coldstore.example";
        Caller customer = new Caller("customer-" + id, "freightflow-web", id, Set.of(Caller.CUSTOMER), Set.of());
        mockMvc.perform(put("/api/v1/notification-settings").with(TestCallers.as(customer))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"language":"%s","recipients":["%s"],"kinds":["shipment-booked","shipment-status",
                                 "payment-failed","payment-captured","payment-released"]}
                                """.formatted(language, recipient)))
                .andExpect(status().isOk());
        return new Org(id, recipient, customer);
    }

    private void publishBooked(Org org, UUID shipment, String trackingNumber) {
        Events.publish("logistics", EventTopics.SHIPMENT, shipment.toString(), booked(org, shipment, trackingNumber));
    }

    private static String booked(Org org, UUID shipment, String trackingNumber) {
        return Events.event("shipment.booked", event -> {
            event.put("aggregateId", shipment.toString());
            payload(event).put("shipmentId", shipment.toString());
            payload(event).put("trackingNumber", trackingNumber);
            payload(event).put("organizationId", org.id().toString());
            payload(event).put("payerOrganizationId", org.id().toString());
        });
    }

    private static String statusChanged(Org org, UUID shipment, String trackingNumber, String status, long sequence,
                                        UUID eventId) {
        return Events.event("shipment.status-changed", event -> {
            event.put("eventId", eventId.toString());
            event.put("aggregateId", shipment.toString());
            event.put("sequence", sequence);
            payload(event).put("shipmentId", shipment.toString());
            payload(event).put("trackingNumber", trackingNumber);
            payload(event).put("organizationId", org.id().toString());
            payload(event).put("payerOrganizationId", org.id().toString());
            payload(event).put("previousStatus", "BOOKED");
            payload(event).put("status", status);
        });
    }

    private boolean processed(UUID eventId) {
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?",
                Integer.class, eventId);
        return count != null && count == 1;
    }

    private static Mailpit.Message first(String recipient) {
        List<Mailpit.Message> messages = Mailpit.to(recipient);
        return messages.isEmpty() ? null : messages.getLast();
    }
}
