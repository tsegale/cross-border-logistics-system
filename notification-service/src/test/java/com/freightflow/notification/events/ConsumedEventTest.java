package com.freightflow.notification.events;

import com.freightflow.notification.support.EventSchemas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the event contracts (ADR 0023): every example of every type notification-service reads
 * deserialises into its own record, with the fields its emails use.
 */
class ConsumedEventTest {

    @Test
    void everyConsumedTypesExampleDeserialisesWithTheFieldsTheEmailsUse() {
        List<JsonNode> consumed = EventSchemas.files().values().stream()
                .filter(schema -> schema.path("x-consumers").valueStream()
                        .anyMatch(consumer -> consumer.asString().equals("notification-service")))
                .toList();
        assertThat(consumed).extracting(schema -> schema.path("title").asString()).containsExactlyInAnyOrder(
                "shipment.booked", "shipment.status-changed", "shipment.payment-failed",
                "hold.captured", "hold.released", "hold.expired");

        for (JsonNode schema : consumed) {
            String type = schema.path("title").asString();
            assertThat(schema.path("x-topic").asString()).as(type).isIn(EventTopics.SHIPMENT, EventTopics.HOLD);
            for (JsonNode example : schema.path("examples")) {
                ConsumedEvent event = ConsumedEvent.parse(example.toString());
                assertThat(event.type()).isEqualTo(type);
                assertThat(event.sequence()).isPositive();
                if (type.startsWith("shipment.")) {
                    assertThat(event.payload().shipmentId()).as(type).isNotNull();
                    assertThat(event.payload().trackingNumber()).as(type).isNotBlank();
                    assertThat(event.payload().organizationId()).as(type).isNotNull();
                    assertThat(event.payload().payerOrganizationId()).as(type).isNotNull();
                } else {
                    assertThat(event.payload().hold().organizationId()).as(type).isNotNull();
                    assertThat(event.payload().hold().amount()).as(type).isNotBlank();
                    assertThat(event.payload().hold().currency()).as(type).isNotBlank();
                    assertThat(event.holdShipmentId()).as(type).isPresent();
                }
            }
        }
    }

    @Test
    void theBookingCarriesItsChargeAndPickup() {
        ConsumedEvent booked = ConsumedEvent.parse(example("shipment.booked"));

        assertThat(booked.payload().quotedTotal().amount()).isEqualTo("1840.5000");
        assertThat(booked.payload().plannedPickupDate()).hasToString("2026-11-25");
        assertThat(booked.payload().origin()).isEqualTo("NAWVB");
    }

    @Test
    void fieldsAProducerAddsLaterAreIgnoredAndBrokenEventsArePoison() {
        String example = example("shipment.status-changed");
        assertThat(ConsumedEvent.parse(example.replace("\"payload\":{", "\"payload\":{\"etaDays\":12,")).sequence())
                .isEqualTo(2);
        assertThatThrownBy(() -> ConsumedEvent.parse("[]")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> ConsumedEvent.parse(example.replace("\"sequence\":2", "\"sequence\":0")))
                .isInstanceOf(PoisonEventException.class);
    }

    private static String example(String type) {
        return EventSchemas.files().get(type + ".schema.json").path("examples").get(0).toString();
    }
}
