package com.freightflow.logistics.shipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * The carrier integration (its OAuth client id) that will report this shipment's tracking events (ADR 0018), and
 * optionally the container and the carrier's booking reference its DCSA events name (ADR 0037).
 */
public record AssignCarrierRequest(
        @NotBlank(message = "carrierId is required")
        @Pattern(regexp = "^[a-z0-9-]{3,64}$", message = "carrierId must be a client id such as carrier-walvis-haulage")
        String carrierId,

        @io.swagger.v3.oas.annotations.media.Schema(description = "The ISO 6346 number of the container the shipment "
                + "travels in: the equipmentReference of the carrier's DCSA events (ADR 0037)", example = "CSQU3054383",
                nullable = true)
        @jakarta.validation.constraints.Size(max = 16, message = "containerNumber must be at most 16 characters")
        String containerNumber,

        @io.swagger.v3.oas.annotations.media.Schema(description = "The carrier's reference for the booking that "
                + "carries this shipment: the BKG documentReference of its DCSA transport events (ADR 0037)",
                example = "KAL2611240042", nullable = true)
        @Pattern(regexp = "^[A-Za-z0-9/_.-]{1,35}$",
                message = "carrierBookingReference must be 1 to 35 letters, digits or / _ . -")
        String carrierBookingReference
) {
    public AssignCarrierRequest(String carrierId) {
        this(carrierId, null, null);
    }

    public AssignCarrierRequest(String carrierId, String containerNumber) {
        this(carrierId, containerNumber, null);
    }
}
