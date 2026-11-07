package com.freightflow.banking.transfer;

import com.freightflow.banking.transfer.dto.CreateTransferRequest;
import com.freightflow.banking.transfer.dto.TransferResponse;
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
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers", description = "Idempotent same-currency and cross-currency ledger transfers")
public class TransferController {

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    @Operation(
            summary = "Execute an idempotent transfer between two ledger accounts",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = true,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = CreateTransferRequest.class),
                            examples = {
                                    @ExampleObject(
                                            name = "Same-currency transfer",
                                            value = """
                                                    {
                                                      "sourceAccount": "FF83NAD000001000",
                                                      "targetAccount": "FF60NAD000000002",
                                                      "amount": 2500.00,
                                                      "currency": "NAD",
                                                      "reference": "FREIGHT-NAWVB-DEHAM-001"
                                                    }
                                                    """
                                    ),
                                    @ExampleObject(
                                            name = "Cross-currency transfer with FX quote",
                                            value = """
                                                    {
                                                      "sourceAccount": "FF83NAD000001000",
                                                      "targetAccount": "FF84EUR000000002",
                                                      "amount": 20000.00,
                                                      "currency": "NAD",
                                                      "reference": "FREIGHT-NAWVB-DEHAM-FX-002",
                                                      "fxQuoteId": "01930000-0000-7000-8000-000000002000"
                                                    }
                                                    """
                                    )
                            }
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "201", description = "Transfer executed",
                            content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "400", description = "Validation error, mistyped account number or missing Idempotency-Key",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Account or FX quote not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Insufficient funds, currency mismatch, expired/used FX quote or key in flight",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Idempotency payload mismatch or invalid transfer combination",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<TransferResponse> createTransfer(
            @Parameter(description = "Idempotency-Key header (RFC draft-ietf-httpapi-idempotency-key-header-08)", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTransferRequest request) {
        TransferResponse created = transferService.createTransfer(request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + created.id())).body(created);
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get a transfer by ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Transfer details",
                            content = @Content(schema = @Schema(implementation = TransferResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Transfer not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public TransferResponse getTransfer(@PathVariable("id") UUID id) {
        return transferService.getTransfer(id);
    }
}
