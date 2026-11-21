package com.freightflow.logistics.events;

import com.freightflow.logistics.support.EventSchemas;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The schemas of logistics' events against the code that writes them (ADR 0023) and the personal-data rule
 * (ADR 0024). No Spring context: this reads files.
 */
class EventSchemaFilesTest {

    /** The names personal data usually goes by. A pseudonymous id (x-pseudonymous) is allowed. */
    private static final Pattern PERSONAL = Pattern.compile(
            "(?i)(.*name|.*e-?mail.*|.*phone.*|.*address.*|.*contact.*|.*birth.*|iban|.*passport.*|.*tax-?id.*"
                    + "|street|city|postcode|zip(code)?|.*accountnumber)");

    @Test
    void everyTypeLogisticsPublishesHasASchemaOnItsTopic() {
        Map<String, JsonNode> schemas = EventSchemas.producedBy("logistics-service");
        assertThat(schemas.keySet()).containsExactlyInAnyOrderElementsOf(
                Arrays.stream(LogisticsEvents.Type.values()).map(LogisticsEvents.Type::typeName).toList());
        for (LogisticsEvents.Type type : LogisticsEvents.Type.values()) {
            JsonNode schema = schemas.get(type.typeName());
            assertThat(schema.path("x-topic").asString()).as(type.typeName()).isEqualTo(type.topic());
            assertThat(schema.path("properties").path("schemaVersion").path("enum").valueStream()
                    .mapToInt(JsonNode::asInt).max().orElseThrow())
                    .as("%s: the code writes the newest schemaVersion", type.typeName())
                    .isEqualTo(type.schemaVersion());
        }
    }

    @Test
    void everyExampleMatchesItsOwnSchema() {
        EventSchemas.producedBy("logistics-service").forEach((type, schema) -> {
            assertThat(schema.path("examples").isEmpty()).as("%s has an example", type).isFalse();
            schema.path("examples").forEach(example -> assertThat(EventSchemas.validate(type, example.toString()))
                    .as("example of %s", type).isEmpty());
        });
    }

    @Test
    void aSchemaRejectsWhatItDoesNotDeclare() {
        JsonNode example = EventSchemas.producedBy("logistics-service").get("shipment.booked").path("examples").get(0);
        String valid = example.toString();
        assertThat(EventSchemas.validate("shipment.booked", valid)).isEmpty();
        // A field the schema does not list: the way personal data would slip in.
        assertThat(EventSchemas.validate("shipment.booked",
                valid.replace("\"quotedTotal\"", "\"consigneeName\":\"A. Person\",\"quotedTotal\""))).isNotEmpty();
        // Money as a JSON number, read through a binary float by most consumers.
        assertThat(EventSchemas.validate("shipment.booked", valid.replace("\"amount\":\"1840.5000\"",
                "\"amount\":1840.5"))).isNotEmpty();
        // Another type's name, and an eventId that is not a UUIDv7.
        assertThat(EventSchemas.validate("shipment.booked", valid.replace("\"shipment.booked\"", "\"shipment.status-changed\"")))
                .isNotEmpty();
        assertThat(EventSchemas.validate("shipment.booked", valid.replace(example.path("eventId").asString(),
                "6f1c2d3e-4b5a-4c6d-8e7f-8091a2b3c4d5"))).isNotEmpty();
    }

    @Test
    void noSchemaCarriesPersonalData() {
        List<String> findings = new ArrayList<>();
        EventSchemas.files().forEach((file, schema) -> personalData(file, schema, "", findings));
        assertThat(findings).as("personal data stays out of events (ADR 0024)").isEmpty();
    }

    @Test
    void thePersonalDataCheckFindsAMarkedField() {
        JsonNode marked = tools.jackson.databind.json.JsonMapper.builder().build().readTree("""
                {"properties": {"payload": {"properties": {
                    "note": {"type": "string", "x-personal-data": true},
                    "consigneeName": {"type": "string"}}}}}
                """);
        List<String> findings = new ArrayList<>();
        personalData("fake.schema.json", marked, "", findings);
        assertThat(findings).hasSize(2);
    }

    private static void personalData(String file, JsonNode node, String path, List<String> findings) {
        if (node.isObject()) {
            if (node.path("x-personal-data").asBoolean(false)) {
                findings.add(file + path + " is marked x-personal-data");
            }
            node.path("properties").propertyNames().forEach(name -> {
                if (PERSONAL.matcher(name).matches()) {
                    findings.add(file + path + "/properties/" + name + " looks like personal data");
                }
            });
            node.properties().forEach(entry -> {
                if (!entry.getKey().equals("examples")) {
                    personalData(file, entry.getValue(), path + "/" + entry.getKey(), findings);
                }
            });
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                personalData(file, node.get(i), path + "/" + i, findings);
            }
        }
    }
}
