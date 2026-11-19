package com.freightflow.banking.approval;

import com.freightflow.banking.approval.dto.ApprovalListResponse;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.approval.dto.DecisionRequest;
import com.freightflow.banking.security.Caller;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
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
import java.util.UUID;

/** Four-eyes approvals for deposits and reversals (ADR 0021): operators decide, auditors read. */
@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Approvals", description = "Deposits and reversals one operator requests and a different operator approves")
public class ApprovalController {

    private final ApprovalService service;

    public ApprovalController(ApprovalService service) {
        this.service = service;
    }

    @PostMapping
    @Operation(
            summary = "Request a deposit or a reversal for a second operator to approve",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateApprovalRequest.class),
                            examples = {
                                    @ExampleObject(name = "deposit", value = """
                                            {
                                              "kind": "DEPOSIT",
                                              "account": "FF14NAD000000001",
                                              "amount": 50000.00,
                                              "currency": "NAD",
                                              "reference": "DEP-BANK-WINDHOEK-001"
                                            }
                                            """),
                                    @ExampleObject(name = "reversal", value = """
                                            {
                                              "kind": "REVERSAL",
                                              "journalEntryId": "0193a0e0-1111-7000-8000-000000000001",
                                              "reason": "Duplicate deposit"
                                            }
                                            """)
                            }
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "201", description = "Request recorded, PENDING",
                            content = @Content(schema = @Schema(implementation = ApprovalResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Fields missing for the kind, invalid amount or currency, missing Idempotency-Key",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Account or journal entry not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Currency mismatch, entry already reversed or a reversal already requested",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Idempotency key reused with a different payload",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<ApprovalResponse> request(
            Caller caller,
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateApprovalRequest body) {
        ApprovalResponse created = service.request(caller, body, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/approvals/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "Approval requests in one status, oldest first (default: the pending queue)")
    public ApprovalListResponse list(
            @RequestParam(value = "status", defaultValue = "PENDING") ApprovalStatus status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return service.list(status, limit);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get an approval request",
            responses = {
                    @ApiResponse(responseCode = "200", description = "The request",
                            content = @Content(schema = @Schema(implementation = ApprovalResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Approval request not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ApprovalResponse get(@PathVariable("id") UUID id) {
        return service.get(id);
    }

    @PostMapping("/{id}/approval")
    @Operation(
            summary = "Approve a request and post its journal entry; the approver must not be the requester",
            responses = {
                    @ApiResponse(responseCode = "200", description = "APPROVED, with the posted entry",
                            content = @Content(schema = @Schema(implementation = ApprovalResponse.class))),
                    @ApiResponse(responseCode = "403", description = "The requester tried to approve their own request",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Approval request not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Already decided, expired, insufficient funds, or the entry was reversed meanwhile",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Idempotency key reused with a different payload",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ApprovalResponse approve(
            Caller caller,
            @PathVariable("id") UUID id,
            @Parameter(description = "Idempotency-Key header", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) DecisionRequest body) {
        return service.approve(caller, id, body == null ? null : body.comment(), idempotencyKey);
    }

    @PostMapping("/{id}/rejection")
    @Operation(
            summary = "Reject a request; nothing is posted. The rejecter must not be the requester",
            responses = {
                    @ApiResponse(responseCode = "200", description = "REJECTED",
                            content = @Content(schema = @Schema(implementation = ApprovalResponse.class))),
                    @ApiResponse(responseCode = "400", description = "No comment",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "403", description = "The requester tried to reject their own request",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Approval request not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Already decided or expired",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ApprovalResponse reject(Caller caller, @PathVariable("id") UUID id,
                                   @Valid @RequestBody(required = false) DecisionRequest body) {
        return service.reject(caller, id, body == null ? null : body.comment());
    }
}
