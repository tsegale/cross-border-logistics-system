package com.freightflow.logistics.events;

import com.freightflow.logistics.support.EventSchemas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Consumer side of the event contracts (ADR 0023): every example of every hold event logistics reads deserialises
 * into logistics' own record, with the fields it uses.
 */
class HoldEventTest {

    @Test
    void everyHoldEventExampleDeserialises() {
        List<String> consumed = EventSchemas.files().values().stream()
                .filter(schema -> schema.path("x-consumers").valueStream()
                        .anyMatch(consumer -> consumer.asString().equals("logistics-service")))
                .map(schema -> schema.path("title").asString())
                .toList();
        assertThat(consumed).containsExactlyInAnyOrder(HoldEvent.EXPIRED, HoldEvent.CAPTURED);

        for (String type : consumed) {
            for (JsonNode example : EventSchemas.files().get(type + ".schema.json").path("examples")) {
                HoldEvent event = HoldEvent.parse(example.toString());
                assertThat(event.type()).isEqualTo(type);
                assertThat(event.eventId()).isNotNull();
                assertThat(event.sequence()).isPositive();
                assertThat(event.holdId()).isEqualTo(event.aggregateId());
                assertThat(event.shipmentId()).as("%s names its shipment", type).isPresent();
            }
        }
        HoldEvent captured = HoldEvent.parse(EventSchemas.files().get("hold.captured.schema.json")
                .path("examples").get(0).toString());
        assertThat(captured.capturedAmount()).isEqualByComparingTo("1840.50");
    }

    @Test
    void aFieldBankingAddsLaterIsIgnored() {
        String example = EventSchemas.files().get("hold.expired.schema.json").path("examples").get(0).toString();
        String withMore = example.replace("\"payload\":{", "\"payload\":{\"addedInVersion2\":\"x\",");

        assertThat(HoldEvent.parse(withMore).holdId()).isEqualTo(HoldEvent.parse(example).holdId());
    }

    @Test
    void unreadableOrIncompleteEventsArePoison() {
        assertThatThrownBy(() -> HoldEvent.parse("not json")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> HoldEvent.parse("{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"hold.expired\"}"))
                .isInstanceOf(PoisonEventException.class);
    }

    @Test
    void aHoldLogisticsDidNotMakeNamesNoShipment() {
        String example = EventSchemas.files().get("hold.expired.schema.json").path("examples").get(0).toString();

        assertThat(HoldEvent.parse(example.replaceAll("\"reference\":\"[^\"]*\"", "\"reference\":\"INVOICE 7\""))
                .shipmentId()).isEmpty();
    }
}
