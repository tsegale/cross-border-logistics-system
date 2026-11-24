package com.freightflow.logistics.dcsa;

import com.freightflow.logistics.shipment.ShipmentStatus;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/** DCSA T&amp;T 2.2 events to milestones on a Walvis Bay to Hamburg move (ADR 0037). */
class DcsaMappingTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @ParameterizedTest(name = "{0} {1} at {2}: {3}")
    @CsvSource({
            "EQUIPMENT, PICK, NAWVB, PICKED_UP",
            "EQUIPMENT, STUF, NAWDH, PICKED_UP",
            "EQUIPMENT, GTIN, NAWVB, AT_ORIGIN_PORT",
            "EQUIPMENT, LOAD, NAWVB, IN_TRANSIT",
            "TRANSPORT, DEPA, NAWVB, IN_TRANSIT",
            "TRANSPORT, ARRI, DEHAM, AT_DESTINATION_PORT",
            "EQUIPMENT, DISC, DEHAM, AT_DESTINATION_PORT",
            "EQUIPMENT, GTOT, DEHAM, OUT_FOR_DELIVERY",
            "EQUIPMENT, DROP, DEHAM, DELIVERED"})
    void theVoyageMapsToTheMilestones(String type, String code, String location, ShipmentStatus status) {
        assertThat(DcsaMapping.milestone(event(type, code, location, "ACT"), "NAWVB", "DEHAM"))
                .hasValueSatisfying(milestone -> {
                    assertThat(milestone.status()).isEqualTo(status);
                    assertThat(milestone.description()).contains("DCSA " + code);
                });
    }

    @ParameterizedTest(name = "{0} {1} at {2} is no milestone")
    @CsvSource({
            // A gate-in at the destination is not arriving at the origin port; a discharge at a transhipment port is
            // not arrival at the destination.
            "EQUIPMENT, GTIN, DEHAM", "EQUIPMENT, DISC, ZADUR", "EQUIPMENT, LOAD, DEHAM",
            "EQUIPMENT, INSP, NAWVB", "TRANSPORT, ARRI, NAWVB", "SHIPMENT, RELS, DEHAM"})
    void eventsElsewhereOrWithoutAMilestoneAreIgnored(String type, String code, String location) {
        assertThat(DcsaMapping.milestone(event(type, code, location, "ACT"), "NAWVB", "DEHAM")).isEmpty();
    }

    @Test
    void plannedAndEstimatedEventsNeverMoveAShipment() {
        assertThat(DcsaMapping.milestone(event("EQUIPMENT", "DISC", "DEHAM", "EST"), "NAWVB", "DEHAM")).isEmpty();
        assertThat(DcsaMapping.milestone(event("EQUIPMENT", "DISC", "DEHAM", "PLN"), "NAWVB", "DEHAM")).isEmpty();
    }

    @Test
    void theEventLocationCountsWhenThereIsNoTransportCall() {
        JsonNode event = JSON.readTree("""
                {"eventType":"EQUIPMENT","eventClassifierCode":"ACT","equipmentEventTypeCode":"DROP",
                 "eventLocation":{"UNLocationCode":"DEHAM"}}""");
        assertThat(DcsaMapping.milestone(event, "NAWVB", "DEHAM")).isPresent();
    }

    private static JsonNode event(String type, String code, String location, String classifier) {
        String codeField = switch (type) {
            case "EQUIPMENT" -> "equipmentEventTypeCode";
            case "TRANSPORT" -> "transportEventTypeCode";
            default -> "shipmentEventTypeCode";
        };
        return JSON.readTree("""
                {"eventType":"%s","eventClassifierCode":"%s","%s":"%s","transportCall":{"UNLocationCode":"%s"}}
                """.formatted(type, classifier, codeField, code, location));
    }
}
