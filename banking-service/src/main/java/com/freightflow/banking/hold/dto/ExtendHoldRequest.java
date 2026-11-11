package com.freightflow.banking.hold.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

@Schema(description = "Move an active hold's expiry later; the amount and any locked FX rate stay as they are")
public record ExtendHoldRequest(
        @Schema(description = "The new expiry: later than the current one, at most holds.max-ttl from now and "
                + "holds.max-lifetime from the hold's creation")
        @NotNull(message = "expiresAt is required")
        Instant expiresAt
) {
}
