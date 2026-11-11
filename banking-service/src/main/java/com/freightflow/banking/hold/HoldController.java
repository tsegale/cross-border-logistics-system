package com.freightflow.banking.hold;

import com.freightflow.banking.hold.dto.CaptureHoldRequest;
import com.freightflow.banking.hold.dto.CreateHoldRequest;
import com.freightflow.banking.hold.dto.HoldResponse;
import com.freightflow.banking.idempotency.IdempotencyService.IdempotentResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * Internal API for logistics-service (ADR 0011). Reachable on the compose network only: the gateway does not
 * route /api/v1/holds (ADR 0015). Phase 3 adds service-to-service credentials.
 */
@RestController
@RequestMapping("/api/v1/holds")
@Tag(name = "Holds (internal)", description = "Authorise, capture and release freight charges. Not routed by the gateway.")
public class HoldController {

    private final HoldService holdService;

    public HoldController(HoldService holdService) {
        this.holdService = holdService;
    }

    @PostMapping
    @Operation(summary = "Authorise: reserve funds on the payer account",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Hold authorised",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Validation error, invalid account number or expiry, missing Idempotency-Key",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Payer account not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Insufficient available funds, account not active, key in flight",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with another body, or the payer is not a customer account",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            })
    public ResponseEntity<HoldResponse> createHold(
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateHoldRequest request) {
        IdempotentResult<HoldResponse> result = holdService.createHold(request, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/api/v1/holds/" + result.body().id()));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.body());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a hold",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The hold",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Hold not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            })
    public HoldResponse getHold(@PathVariable("id") UUID id) {
        return holdService.getHold(id);
    }

    @PostMapping("/{id}/capture")
    @Operation(summary = "Capture a hold, fully or in part, once: payer to FREIGHT_REVENUE at the locked rate",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Captured",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Hold not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Hold not active or expired, key in flight",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Capture exceeds the hold, or key reused with another body",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            })
    public HoldResponse capture(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureHoldRequest request) {
        return holdService.capture(id, request, idempotencyKey);
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "Release a hold: free the reservation, post nothing",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Released",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Hold not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Hold not active, key in flight",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            })
    public HoldResponse release(
            @PathVariable("id") UUID id,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return holdService.release(id, idempotencyKey);
    }
}
