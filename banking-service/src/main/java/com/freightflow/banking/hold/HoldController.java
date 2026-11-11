package com.freightflow.banking.hold;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.hold.dto.CaptureHoldRequest;
import com.freightflow.banking.hold.dto.CreateHoldRequest;
import com.freightflow.banking.hold.dto.ExtendHoldRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;
import java.util.UUID;

/**
 * Internal API for logistics-service (ADR 0011). The gateway does not route /api/v1/holds (ADR 0015), and every call
 * needs logistics-service's own token with the {@code banking.holds} scope (ADR 0019).
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
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Payer account not found",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Insufficient available funds, account not active, key in flight",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Idempotency-Key reused with another body, or the payer is not a customer account",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "Database unavailable or account lock timeout: retry with the same Idempotency-Key",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
            })
    public ResponseEntity<HoldResponse> createHold(
            Caller caller,
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateHoldRequest request) {
        IdempotentResult<HoldResponse> result = holdService.createHold(caller, request, idempotencyKey);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.httpStatus())
                .location(URI.create("/api/v1/holds/" + result.body().id()));
        if (result.replayed()) {
            builder.header("Idempotent-Replayed", "true");
        }
        return builder.body(result.body());
    }

    @GetMapping
    @Operation(summary = "Find the holds made for a reference, newest first (at most 50)",
            responses = @ApiResponse(responseCode = "200", description = "Holds with that reference"))
    public List<HoldResponse> findByReference(@RequestParam("reference") String reference) {
        return holdService.findByReference(reference);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get a hold",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The hold",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Hold not found",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
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
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Hold not active or expired, key in flight",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Capture exceeds the hold, or key reused with another body",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "Database unavailable or account lock timeout: retry with the same Idempotency-Key",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
            })
    public HoldResponse capture(
            Caller caller,
            @PathVariable("id") UUID id,
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) CaptureHoldRequest request) {
        return holdService.capture(caller, id, request, idempotencyKey);
    }

    @PostMapping("/{id}/extension")
    @Operation(summary = "Extend an active hold: move its expiry later, keep its amount and locked rate",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Extended",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Validation error or missing Idempotency-Key",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Hold not found",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Hold not active or already expired, key in flight",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "expiresAt not later, or beyond max-ttl or max-lifetime; key reused with another body",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "Database unavailable or account lock timeout: retry with the same Idempotency-Key",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
            })
    public HoldResponse extend(
            Caller caller,
            @PathVariable("id") UUID id,
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody ExtendHoldRequest request) {
        return holdService.extend(caller, id, request, idempotencyKey);
    }

    @PostMapping("/{id}/release")
    @Operation(summary = "Release a hold: free the reservation, post nothing",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Released",
                            content = @Content(schema = @Schema(implementation = HoldResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Hold not found",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Hold not active, key in flight",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "503", description = "Database unavailable or account lock timeout: retry with the same Idempotency-Key",
                            content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = ProblemDetail.class)))
            })
    public HoldResponse release(
            Caller caller,
            @PathVariable("id") UUID id,
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        return holdService.release(caller, id, idempotencyKey);
    }
}
