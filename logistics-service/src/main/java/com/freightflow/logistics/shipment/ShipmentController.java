package com.freightflow.logistics.shipment;

import com.freightflow.logistics.idempotency.IdempotencyService.IdempotentResult;
import com.freightflow.logistics.shipment.dto.CancelShipmentRequest;
import com.freightflow.logistics.shipment.dto.CreateShipmentRequest;
import com.freightflow.logistics.shipment.dto.ShipmentPageResponse;
import com.freightflow.logistics.shipment.dto.ShipmentResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/shipments")
@Tag(name = "Shipments", description = "Shipment bookings, lifecycle state machine, and keyset listing")
public class ShipmentController {

    private final ShipmentService shipmentService;

    public ShipmentController(ShipmentService shipmentService) {
        this.shipmentService = shipmentService;
    }

    @PostMapping
    @Operation(summary = "Book a shipment from a valid freight quote in AWAITING_PAYMENT status")
    public ResponseEntity<ShipmentResponse> createShipment(
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateShipmentRequest request) {
        IdempotentResult<ShipmentResponse> result = shipmentService.createShipment(request, idempotencyKey);
        ShipmentResponse created = result.body();
        ResponseEntity.BodyBuilder builder = ResponseEntity
                .status(result.httpStatus())
                .location(URI.create("/api/v1/shipments/" + created.id()))
                .header(HttpHeaders.ETAG, ETags.format(created.version()));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(created);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Fetch a shipment by ID with its current ETag version")
    public ResponseEntity<ShipmentResponse> getShipment(@PathVariable UUID id) {
        ShipmentResponse shipment = shipmentService.getShipment(id);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.format(shipment.version()))
                .body(shipment);
    }

    @GetMapping
    @Operation(summary = "List shipments ordered by (created_at DESC, id DESC) with optional status filter and keyset pagination")
    public ShipmentPageResponse listShipments(
            @RequestParam(name = "status", required = false) ShipmentStatus status,
            @RequestParam(name = "cursor", required = false) String cursor,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return shipmentService.listShipments(status, cursor, size);
    }

    @PostMapping("/{id}/cancellation")
    @Operation(summary = "Cancel a shipment before it reaches IN_TRANSIT")
    public ResponseEntity<ShipmentResponse> cancelShipment(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody(required = false) CancelShipmentRequest request) {
        ShipmentResponse cancelled = shipmentService.cancelShipment(id, request, ifMatch);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.format(cancelled.version()))
                .body(cancelled);
    }
}
