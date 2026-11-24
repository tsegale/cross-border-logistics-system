package com.freightflow.logistics.shipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The carrier integration (its OAuth client id) that will report this shipment's tracking events (ADR 0018), and
 * optionally the container its DCSA events name (ADR 0037).
 */
public record AssignCarrierRequest(
        @NotBlank(message = "carrierId is required")
        @Pattern(regexp = "^[a-z0-9-]{3,64}$", message = "carrierId must be a client id such as carrier-walvis-haulage")
        String carrierId,

        @io.swagger.v3.oas.annotations.media.Schema(description = "The ISO 6346 number of the container the shipment "
                + "travels in: the equipmentReference of the carrier's DCSA events (ADR 0037)", example = "CSQU3054383",
                nullable = true)
        @jakarta.validation.constraints.Size(max = 16, message = "containerNumber must be at most 16 characters")
        String containerNumber
) {
    public AssignCarrierRequest(String carrierId) {
        this(carrierId, null);
    }
}
