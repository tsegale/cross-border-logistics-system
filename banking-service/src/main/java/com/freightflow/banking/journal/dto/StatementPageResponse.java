package com.freightflow.banking.journal.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.UUID;

@Schema(description = "Keyset-paginated account statement (newest first)")
public record StatementPageResponse(
        UUID accountId,
        String accountNumber,
        String currency,
        List<StatementLineResponse> items,
        String nextCursor
) {
}
