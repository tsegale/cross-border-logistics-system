package com.freightflow.logistics.dcsa;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * DCSA T&amp;T 2.2 events shaped like the carrier simulator's ({@code infra/partners/carrier.py}), checked against the
 * committed specification by {@link DcsaContractTest}.
 */
final class DcsaTestEvents {

    static final String SECRET = "MTIzNDU2Nzg5MDEyMzQ1Njc4OTAxMjM0NTY3ODkwMTI=";
    static final String SUBSCRIPTION = "6f2d0a6e-1c1d-4f53-9a64-1c4e8f0b7a21";
    static final String CARRIER = "carrier-kalahari-freight";

    private DcsaTestEvents() {
    }

    static String equipment(String code, String container, String location, Instant created) {
        return equipment(UUID.randomUUID(), code, container, location, created);
    }

    static String equipment(UUID eventId, String code, String container, String location, Instant created) {
        String at = created.truncatedTo(ChronoUnit.MILLIS).toString();
        return """
                {"eventID":"%s","eventCreatedDateTime":"%s","eventType":"EQUIPMENT","eventClassifierCode":"ACT",\
                "eventDateTime":"%s","equipmentEventTypeCode":"%s","equipmentReference":"%s","ISOEquipmentCode":"22RT",\
                "emptyIndicatorCode":"LADEN","transportCall":{"transportCallID":"%s","UNLocationCode":"%s",\
                "modeOfTransport":"VESSEL"}}""".formatted(eventId, at, at, code, container, UUID.randomUUID(), location);
    }

    static String transport(String code, String location, Instant created) {
        String at = created.truncatedTo(ChronoUnit.MILLIS).toString();
        return """
                {"eventID":"%s","eventCreatedDateTime":"%s","eventType":"TRANSPORT","eventClassifierCode":"ACT",\
                "eventDateTime":"%s","transportEventTypeCode":"%s","transportCall":{"transportCallID":"%s",\
                "UNLocationCode":"%s","modeOfTransport":"VESSEL","carrierVoyageNumber":"2647N"}}"""
                .formatted(UUID.randomUUID(), at, at, code, UUID.randomUUID(), location);
    }

    /** The vessel the voyages of these tests sail on: the IMO number from DCSA's examples (its check digit holds). */
    static final String VESSEL = "9321483";

    /** An equipment event on a vessel's voyage (LOAD, DISC): what links the vessel's transport events to it. */
    static String equipmentOnVessel(String code, String container, String location, Instant created, String vessel,
                                    String voyage) {
        String at = created.truncatedTo(ChronoUnit.MILLIS).toString();
        return """
                {"eventID":"%s","eventCreatedDateTime":"%s","eventType":"EQUIPMENT","eventClassifierCode":"ACT",                "eventDateTime":"%s","equipmentEventTypeCode":"%s","equipmentReference":"%s","ISOEquipmentCode":"22RT",                "emptyIndicatorCode":"LADEN","transportCall":{"transportCallID":"%s","carrierServiceCode":"NAE",                "exportVoyageNumber":"%s","importVoyageNumber":"%s","UNLocationCode":"%s","modeOfTransport":"VESSEL",                "vessel":{"vesselIMONumber":"%s","vesselName":"Kalahari Express"}}}"""
                .formatted(UUID.randomUUID(), at, at, code, container, UUID.randomUUID(), voyage, voyage, location,
                        vessel);
    }

    /** A vessel's departure or arrival, with its transport call and, when given, the bookings it carries (BKG). */
    static String transportOnVessel(String code, String location, Instant created, String vessel, String voyage,
                                    String... bookingReferences) {
        String at = created.truncatedTo(ChronoUnit.MILLIS).toString();
        StringBuilder references = new StringBuilder();
        for (String reference : bookingReferences) {
            references.append(references.isEmpty() ? "" : ",")
                    .append("{\"documentReferenceType\":\"BKG\",\"documentReferenceValue\":\"").append(reference)
                    .append("\"}");
        }
        return """
                {"eventID":"%s","eventCreatedDateTime":"%s","eventType":"TRANSPORT","eventClassifierCode":"ACT",                "eventDateTime":"%s","transportEventTypeCode":"%s","transportCall":{"transportCallID":"%s",                "carrierServiceCode":"NAE","exportVoyageNumber":"%s","importVoyageNumber":"%s","UNLocationCode":"%s",                "modeOfTransport":"VESSEL","vessel":{"vesselIMONumber":"%s","vesselName":"Kalahari Express"}}%s}"""
                .formatted(UUID.randomUUID(), at, at, code, UUID.randomUUID(), voyage, voyage, location, vessel,
                        references.isEmpty() ? "" : ",\"documentReferences\":[" + references + "]");
    }

    static String notification(String... events) {
        return "[" + String.join(",", events) + "]";
    }
}
