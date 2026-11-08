package com.freightflow.logistics.shipment;

import com.freightflow.logistics.idempotency.IdempotencyService.IdempotentResult;
import com.freightflow.logistics.shipment.ShipmentService.PublicTrackingResult;
import com.freightflow.logistics.shipment.dto.CancelShipmentRequest;
import com.freightflow.logistics.shipment.dto.CreateShipmentRequest;
import com.freightflow.logistics.shipment.dto.PublicTrackingResponse;
import com.freightflow.logistics.shipment.dto.RecordTrackingEventRequest;
import com.freightflow.logistics.shipment.dto.ShipmentPageResponse;
import com.freightflow.logistics.shipment.dto.ShipmentResponse;
import com.freightflow.logistics.shipment.dto.TrackingEventResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
@Tag(name = "Shipments", description = "Shipment bookings, lifecycle state machine, tracking events, and keyset listing")
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

    @GetMapping("/track/{trackingNumber}")
    @Operation(summary = "Public shipment tracking by tracking number with local IANA time zone timestamps and no customer PII")
    public ResponseEntity<PublicTrackingResponse> trackByTrackingNumber(
            @PathVariable String trackingNumber) {
        PublicTrackingResult result = shipmentService.trackByTrackingNumber(trackingNumber);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, ETags.format(result.version()))
                .body(result.body());
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

    @PostMapping("/{id}/tracking-events")
    @Operation(summary = "Record a tracking event with out-of-order and duplicate-carrier-event safety")
    public ResponseEntity<TrackingEventResponse> recordTrackingEvent(
            @PathVariable UUID id,
            @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @Valid @RequestBody RecordTrackingEventRequest request) {
        TrackingEventResponse recorded = shipmentService.recordTrackingEvent(id, request, ifMatch);
        HttpStatus status = recorded.duplicateIgnored() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .header(HttpHeaders.ETAG, ETags.format(recorded.shipmentVersion()))
                .body(recorded);
    }

    @PostMapping("/{id}/cancellation")
    @Operation(summary = "Cancel a shipment before it reaches PICKED_UP")
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
