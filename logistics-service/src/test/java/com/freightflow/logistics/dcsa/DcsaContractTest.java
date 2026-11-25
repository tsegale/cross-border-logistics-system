package com.freightflow.logistics.dcsa;

import com.atlassian.oai.validator.OpenApiInteractionValidator;
import com.atlassian.oai.validator.model.Request;
import com.atlassian.oai.validator.model.SimpleRequest;
import com.atlassian.oai.validator.model.SimpleResponse;
import com.atlassian.oai.validator.report.ValidationReport;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The DCSA events the tests send, and the simulator emits in the same shape, are what T&amp;T 2.2.0 allows: checked as a
 * {@code GET /v2/events} response against the committed, resolved specification (docs/api/dcsa-tnt-2.2.0.json,
 * Apache 2.0, from SwaggerHub dcsaorg/DCSA_TNT/2.2.0). An event code, a
 * type or a field the standard does not have fails here.
 */
class DcsaContractTest {

    static final Path SPEC = Path.of("..", "docs", "api", "dcsa-tnt-2.2.0.json");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static OpenApiInteractionValidator validator;

    @BeforeAll
    static void load() throws Exception {
        // allOf merged into one schema per event, so the parts are checked together, as one object.
        validator = OpenApiInteractionValidator.createForInlineApiSpecification(loadable(Files.readString(SPEC)))
                .withResolveCombinators(true).build();
    }

    /**
     * Two corrections, in memory only; the committed document stays as DCSA published it.
     * <ul>
     *   <li>Four array query parameters (shipmentEventTypeCode, documentTypeCode, transportEventTypeCode,
     *       equipmentEventTypeCode) have a comma-separated string as their default, which the parser refuses
     *       ("default is not of type array"). Request parameter defaults play no part in validating an event.</li>
     *   <li>{@code documentReferenceType}'s enum is written {@code ["BKG (Booking)", "TRD (Transport Document)"]}: the
     *       description pasted into the values. The schema's own example, its description of the field and what
     *       carriers send is {@code BKG} and {@code TRD}, so the enum is read as those codes.</li>
     * </ul>
     */
    static String loadable(String spec) {
        JsonNode tree = JSON.readTree(spec);
        tree.findParents("documentReferenceType").forEach(parent -> {
            JsonNode field = parent.path("documentReferenceType");
            if (field instanceof ObjectNode object && field.path("enum").isArray()) {
                var codes = object.putArray("enum");
                codes.add("BKG");
                codes.add("TRD");
            }
        });
        tree.findParents("schema").forEach(parent -> {
            JsonNode schema = parent.path("schema");
            if (parent.has("in") && schema instanceof ObjectNode object && "array".equals(schema.path("type").asString(""))
                    && schema.has("default") && !schema.path("default").isArray()) {
                object.remove("default");
            }
        });
        return tree.toString();
    }

    @Test
    void theTestAndSimulatorEventsAreDcsaEvents() {
        Instant now = Instant.parse("2026-11-24T08:00:00Z");
        String body = DcsaTestEvents.notification(
                DcsaTestEvents.equipment("GTIN", "CSQU3054383", "NAWVB", now),
                DcsaTestEvents.equipment("LOAD", "CSQU3054383", "NAWVB", now),
                DcsaTestEvents.transport("DEPA", "NAWVB", now),
                DcsaTestEvents.equipmentOnVessel("LOAD", "CSQU3054383", "NAWVB", now, DcsaTestEvents.VESSEL, "2647N"),
                DcsaTestEvents.transportOnVessel("ARRI", "DEHAM", now, DcsaTestEvents.VESSEL, "2647N", "KAL2611240042"),
                DcsaTestEvents.equipment("DROP", "CSQU3054383", "DEHAM", now));

        ValidationReport report = validate(body);

        assertThat(report.getMessages()).as(report.toString()).isEmpty();
    }

    @Test
    void anEventTheStandardDoesNotKnowIsCaught() {
        String wrong = DcsaTestEvents.notification(DcsaTestEvents.equipment("GTIN", "CSQU3054383", "NAWVB",
                Instant.parse("2026-11-24T08:00:00Z")).replace("\"GTIN\"", "\"TELEPORT\""));

        assertThat(validate(wrong).hasErrors()).isTrue();
    }

    @Test
    void aFieldTheStandardDoesNotHaveIsCaught() {
        String extra = DcsaTestEvents.notification(DcsaTestEvents.equipment("GTIN", "CSQU3054383", "NAWVB",
                Instant.parse("2026-11-24T08:00:00Z")).replace("\"ISOEquipmentCode\"", "\"containerColour\":\"red\",\"ISOEquipmentCode\""));

        assertThat(validate(extra).hasErrors()).isTrue();
    }

    private static ValidationReport validate(String body) {
        Request request = SimpleRequest.Builder.get("/v2/events").withHeader("Accept", "application/json").build();
        return validator.validate(request, SimpleResponse.Builder.status(200).withContentType("application/json")
                .withHeader("API-Version", "2.2.0").withHeader("Current-Page", "/v2/events?cursor=fE9mZnNldHw9MTAmbGltaXQ9MTA").withBody(body).build());
    }
}
