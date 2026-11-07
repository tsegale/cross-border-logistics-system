package com.freightflow.logistics.shipment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PartyDto(
        @NotBlank(message = "name is required")
        @Size(max = 160, message = "name must be at most 160 characters")
        String name,

        @NotBlank(message = "address is required")
        @Size(max = 300, message = "address must be at most 300 characters")
        String address,

        @NotBlank(message = "contact is required")
        @Size(max = 160, message = "contact must be at most 160 characters")
        String contact
) {
}
