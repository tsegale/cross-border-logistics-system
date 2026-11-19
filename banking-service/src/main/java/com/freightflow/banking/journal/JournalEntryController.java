package com.freightflow.banking.journal;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.journal.dto.ReversalRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/journal-entries")
@Tag(name = "Journal Entries", description = "Immutable double-entry journal entries; reversals are four-eyes approvals")
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
}
