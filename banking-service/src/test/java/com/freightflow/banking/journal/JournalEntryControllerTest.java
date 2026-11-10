package com.freightflow.banking.journal;

import com.freightflow.banking.common.ApiExceptionHandler;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.journal.dto.PostingResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(JournalEntryController.class)
@Import(ApiExceptionHandler.class)
class JournalEntryControllerTest {

    private static final UUID ENTRY = UUID.fromString("01930000-0000-7000-8000-000000004000");
    private static final UUID REVERSAL = UUID.fromString("01930000-0000-7000-8000-000000004001");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private LedgerService ledgerService;

    @Test
    void entryIsReturnedWithItsPostings() throws Exception {
        given(ledgerService.getJournalEntry(ENTRY)).willReturn(entry(ENTRY, JournalEntryType.TRANSFER, null));

        mockMvc.perform(get("/api/v1/journal-entries/{id}", ENTRY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entryType").value("TRANSFER"))
                .andExpect(jsonPath("$.postings.length()").value(2));
    }

    @Test
    void reversalIs201WithLocation() throws Exception {
        given(ledgerService.reverse(eq(ENTRY), any(), eq("rev-1")))
                .willReturn(entry(REVERSAL, JournalEntryType.REVERSAL, ENTRY));

        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversals", ENTRY)
                        .header("Idempotency-Key", "rev-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Duplicate invoice\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/journal-entries/" + REVERSAL))
                .andExpect(jsonPath("$.reversesEntryId").value(ENTRY.toString()));
    }

    @Test
    void secondReversalIs409() throws Exception {
        given(ledgerService.reverse(eq(ENTRY), any(), eq("rev-2")))
                .willThrow(new JournalEntryAlreadyReversedException(ENTRY, "already reversed"));

        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversals", ENTRY).header("Idempotency-Key", "rev-2"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/journal-entry-already-reversed"));
    }

    @Test
    void reasonLongerThan160CharactersIsAValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/journal-entries/{id}/reversals", ENTRY)
                        .header("Idempotency-Key", "rev-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"" + "x".repeat(161) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/validation-failed"));
    }

    @Test
    void unknownEntryIs404() throws Exception {
        given(ledgerService.getJournalEntry(ENTRY)).willThrow(new JournalEntryNotFoundException(ENTRY));

        mockMvc.perform(get("/api/v1/journal-entries/{id}", ENTRY))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/journal-entry-not-found"));
    }

    @Test
    void malformedIdIs400Not500() throws Exception {
        mockMvc.perform(get("/api/v1/journal-entries/{id}", "not-a-uuid"))
                .andExpect(status().isBadRequest());
    }

    private static JournalEntryResponse entry(UUID id, JournalEntryType type, UUID reverses) {
        Instant at = Instant.parse("2026-11-10T10:00:00Z");
        return new JournalEntryResponse(id, type, "REF", reverses, null, null, at, List.of(
                new PostingResponse(UUID.randomUUID(), UUID.randomUUID(), "FF83NAD000001000", new BigDecimal("-10.00"), "NAD", new BigDecimal("90.00"), at),
                new PostingResponse(UUID.randomUUID(), UUID.randomUUID(), "FF60NAD000001001", new BigDecimal("10.00"), "NAD", new BigDecimal("10.00"), at)));
    }
}
