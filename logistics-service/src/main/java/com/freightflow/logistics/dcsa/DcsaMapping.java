package com.freightflow.logistics.dcsa;

import com.freightflow.logistics.shipment.ShipmentStatus;
import tools.jackson.databind.JsonNode;

import java.util.Optional;

/**
 * DCSA Track and Trace 2.2 events to FreightFlow's milestones (ADR 0037, ADR 0009). Only actual events
 * ({@code eventClassifierCode} ACT) move a shipment; planned and estimated ones are stored and ignored. For a move
 * from the shipment's origin to its destination:
 * <pre>
 *   EQUIPMENT PICK or STUF                   PICKED_UP            the container is at the shipper
 *   EQUIPMENT GTIN at the origin             AT_ORIGIN_PORT       gated in at the port of loading
 *   EQUIPMENT LOAD at the origin             IN_TRANSIT           loaded on the vessel
 *   TRANSPORT DEPA at the origin             IN_TRANSIT           the vessel left
 *   EQUIPMENT DISC at the destination        AT_DESTINATION_PORT
 *   TRANSPORT ARRI at the destination        AT_DESTINATION_PORT
 *   EQUIPMENT GTOT at the destination        OUT_FOR_DELIVERY     gated out: customs released it
 *   EQUIPMENT DROP at the destination        DELIVERED            dropped off at the consignee
 * </pre>
 * Customs has no event in T&amp;T 2.2: a gate-out after discharge means the container was released, and the skipped
 * CUSTOMS_CLEARED milestone is recorded as skipped (ADR 0009).
 */
public final class DcsaMapping {

    private DcsaMapping() {
    }

    public record Milestone(ShipmentStatus status, String location, String description) {
    }

    public static Optional<Milestone> milestone(JsonNode event, String origin, String destination) {
        if (!"ACT".equals(event.path("eventClassifierCode").asString(""))) {
            return Optional.empty();
        }
        String location = location(event);
        return switch (event.path("eventType").asString("")) {
            case "EQUIPMENT" -> equipment(event.path("equipmentEventTypeCode").asString(""), location, origin,
                    destination);
            case "TRANSPORT" -> transport(event.path("transportEventTypeCode").asString(""), location, origin,
                    destination);
            default -> Optional.empty();
        };
    }

    private static Optional<Milestone> equipment(String code, String location, String origin, String destination) {
        return switch (code) {
            case "PICK", "STUF" -> Optional.of(new Milestone(ShipmentStatus.PICKED_UP,
                    location == null ? origin : location, "Container " + ("PICK".equals(code) ? "picked up" : "stuffed")
                    + " (DCSA " + code + ")"));
            case "GTIN" -> at(origin, location, ShipmentStatus.AT_ORIGIN_PORT, "Gated in at the port of loading (DCSA GTIN)");
            case "LOAD" -> at(origin, location, ShipmentStatus.IN_TRANSIT, "Loaded on the vessel (DCSA LOAD)");
            case "DISC" -> at(destination, location, ShipmentStatus.AT_DESTINATION_PORT,
                    "Discharged at the port of discharge (DCSA DISC)");
            case "GTOT" -> at(destination, location, ShipmentStatus.OUT_FOR_DELIVERY,
                    "Gated out for delivery after customs release (DCSA GTOT)");
            case "DROP" -> at(destination, location, ShipmentStatus.DELIVERED, "Dropped off at the consignee (DCSA DROP)");
            default -> Optional.empty();
        };
    }

    private static Optional<Milestone> transport(String code, String location, String origin, String destination) {
        return switch (code) {
            case "DEPA" -> at(origin, location, ShipmentStatus.IN_TRANSIT, "Vessel departed the port of loading (DCSA DEPA)");
            case "ARRI" -> at(destination, location, ShipmentStatus.AT_DESTINATION_PORT,
                    "Vessel arrived at the port of discharge (DCSA ARRI)");
            default -> Optional.empty();
        };
    }

    /** The milestone, if the event happened where it has to: GTIN at the destination is not AT_ORIGIN_PORT. */
    private static Optional<Milestone> at(String expected, String location, ShipmentStatus status, String description) {
        return expected.equals(location) ? Optional.of(new Milestone(status, expected, description)) : Optional.empty();
    }

    /** The event's UN/LOCODE: its transport call's, else its eventLocation's. */
    static String location(JsonNode event) {
        String call = event.path("transportCall").path("UNLocationCode").asString("");
        if (!call.isBlank()) {
            return call;
        }
        String own = event.path("eventLocation").path("UNLocationCode").asString("");
        return own.isBlank() ? null : own;
    }
}
