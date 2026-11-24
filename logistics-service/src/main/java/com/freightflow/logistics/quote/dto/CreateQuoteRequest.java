package com.freightflow.logistics.quote.dto;

import com.freightflow.logistics.customs.dto.GoodsLineRequest;
import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.PayerRole;
import com.freightflow.logistics.quote.TransportMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record CreateQuoteRequest(
        @NotBlank(message = "origin is required")
        String origin,

        @NotBlank(message = "destination is required")
        String destination,

        @NotNull(message = "mode is required")
        TransportMode mode,

        @NotNull(message = "incoterm is required")
        Incoterm incoterm,

        @NotNull(message = "payerRole is required: SHIPPER or CONSIGNEE")
        PayerRole payerRole,

        @NotEmpty(message = "packages must not be empty")
        List<@Valid PackageSpecDto> packages,

        @Schema(description = "Optional currency to show an indicative total in, using banking-service's rates. "
                + "The quote itself is always priced in the tariff currency.", example = "NAD", nullable = true)
        @Size(min = 3, max = 3, message = "indicativeCurrency must be a 3-letter ISO 4217 code")
        String indicativeCurrency,

        @Schema(description = "The goods, for an estimate of duties and import VAT into Germany or Namibia "
                + "(ADR 0035). Optional: without them the quote is freight only.", nullable = true)
        @Size(max = 50, message = "goods must have at most 50 lines")
        List<@Valid GoodsLineRequest> goods
) {
    public CreateQuoteRequest(String origin, String destination, TransportMode mode, Incoterm incoterm,
                              PayerRole payerRole, List<PackageSpecDto> packages, String indicativeCurrency) {
        this(origin, destination, mode, incoterm, payerRole, packages, indicativeCurrency, null);
    }
}
