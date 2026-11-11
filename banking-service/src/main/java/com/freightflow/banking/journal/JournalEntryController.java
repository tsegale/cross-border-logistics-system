package com.freightflow.banking.journal;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.journal.dto.ReversalRequest;
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
@RequestMapping("/api/v1/journal-entries")
@Tag(name = "Journal Entries", description = "Immutable double-entry journal entries and reversals")
public class JournalEntryController {

    private final LedgerService ledgerService;

    public JournalEntryController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get a journal entry and its postings by ID",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Journal entry details",
                            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Journal entry not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public JournalEntryResponse getJournalEntry(Caller caller, @PathVariable("id") UUID id) {
        return ledgerService.getJournalEntry(caller, id);
    }

    @PostMapping("/{id}/reversals")
    @Operation(
            summary = "Reverse a journal entry by appending a compensating entry with opposite postings",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(
                    required = false,
                    content = @Content(
                            mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ReversalRequest.class),
                            examples = @ExampleObject(
                                    value = """
                                            {
                                              "reason": "Duplicate transfer correction"
                                            }
                                            """
                            )
                    )
            ),
            responses = {
                    @ApiResponse(responseCode = "201", description = "Reversal entry created",
                            content = @Content(schema = @Schema(implementation = JournalEntryResponse.class))),
                    @ApiResponse(responseCode = "404", description = "Original journal entry not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Already reversed, insufficient funds or idempotency key in flight",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "422", description = "Idempotency key reused with a different payload",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<JournalEntryResponse> reverseJournalEntry(
            Caller caller,
            @PathVariable("id") UUID id,
            @Parameter(description = "Idempotency-Key header (RFC draft-ietf-httpapi-idempotency-key-header-08)", required = true)
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody(required = false) ReversalRequest request) {
        JournalEntryResponse reversal = ledgerService.reverse(caller, id, request, idempotencyKey);
        return ResponseEntity.created(URI.create("/api/v1/journal-entries/" + reversal.id())).body(reversal);
    }
}
