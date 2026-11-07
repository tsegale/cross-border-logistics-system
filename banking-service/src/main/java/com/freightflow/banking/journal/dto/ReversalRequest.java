package com.freightflow.banking.journal.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Size;

@Schema(description = "Optional reason when reversing a journal entry")
public record ReversalRequest(
        @Schema(description = "Reason for reversing the entry", example = "Duplicate booking charge correction")
        @Size(max = 160, message = "reason must be at most 160 characters")
        String reason
) {
}
