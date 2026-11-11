package com.freightflow.logistics.shipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** The carrier integration (its OAuth client id) that will report this shipment's tracking events (ADR 0018). */
public record AssignCarrierRequest(
        @NotBlank(message = "carrierId is required")
        @Pattern(regexp = "^[a-z0-9-]{3,64}$", message = "carrierId must be a client id such as carrier-walvis-haulage")
        String carrierId
) {
}
