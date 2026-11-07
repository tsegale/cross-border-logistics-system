package com.freightflow.logistics.shipment;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque Base64URL cursor for keyset pagination over {@code (created_at DESC, id DESC)} on {@code shipments}.
 */
public record ShipmentCursor(String status, Instant createdAt, UUID shipmentId) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public String encode() {
        byte[] json = JSON.writeValueAsBytes(this);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
    }

    public static ShipmentCursor decode(String rawCursor, String expectedStatusFilter) {
        if (rawCursor == null || rawCursor.isBlank()) {
            throw new InvalidShipmentCursorException("Cursor must not be blank");
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(rawCursor.trim().getBytes(StandardCharsets.UTF_8));
            ShipmentCursor cursor = JSON.readValue(decoded, ShipmentCursor.class);
            if (cursor == null || cursor.createdAt() == null || cursor.shipmentId() == null) {
                throw new InvalidShipmentCursorException("Cursor payload is incomplete");
            }
            if (!Objects.equals(cursor.status(), expectedStatusFilter)) {
                throw new InvalidShipmentCursorException("Cursor status filter does not match requested status");
            }
            return cursor;
        } catch (IllegalArgumentException | JacksonException ex) {
            throw new InvalidShipmentCursorException("Malformed pagination cursor");
        }
    }
}
