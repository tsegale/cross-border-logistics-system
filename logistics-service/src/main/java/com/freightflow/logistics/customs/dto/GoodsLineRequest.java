package com.freightflow.logistics.customs.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "One line of the goods declared for customs (ADR 0035)")
public record GoodsLineRequest(
        @Schema(description = "Tariff code: 10 digits (TARIC) into the EU, 8 digits (SACU tariff) into Namibia",
                example = "0304741100")
        @NotBlank(message = "hsCode is required")
        @Pattern(regexp = "^[0-9]{6,10}$", message = "hsCode must be 6 to 10 digits, without dots or spaces")
        String hsCode,

        @Schema(example = "Frozen Cape hake fillets, skinless, 10 kg cartons")
        @NotBlank(message = "description is required")
        @Size(max = 200, message = "description must be at most 200 characters")
        String description,

        @Schema(description = "ISO 3166-1 alpha-2 country of origin", example = "NA")
        @NotBlank(message = "originCountry is required")
        @Pattern(regexp = "^[A-Z]{2}$", message = "originCountry must be an ISO 3166-1 alpha-2 code")
        String originCountry,

        @Schema(example = "1200")
        @NotNull(message = "quantity is required")
        @Min(value = 1, message = "quantity must be at least 1")
        Integer quantity,

        @Schema(example = "12000.000")
        @NotNull(message = "netWeightKg is required")
        @DecimalMin(value = "0.001", message = "netWeightKg must be positive")
        BigDecimal netWeightKg,

        @Schema(description = "The goods' value for customs, in customsValueCurrency: the transaction value of the "
                + "goods alone, FOB, without freight or insurance", example = "48000.00")
        @NotNull(message = "customsValue is required")
        @DecimalMin(value = "0.01", message = "customsValue must be positive")
        BigDecimal customsValue,

        @Schema(example = "EUR")
        @NotBlank(message = "customsValueCurrency is required")
        @Pattern(regexp = "^[A-Z]{3}$", message = "customsValueCurrency must be an ISO 4217 code")
        String customsValueCurrency,

        @Schema(description = "The proof of preferential origin the goods travel with; without one the general "
                + "duty applies", nullable = true)
        @Valid
        OriginProof originProof
) {

    @Schema(description = "A proof of origin under the EU-SADC EPA, Protocol 1 Article 19: a movement certificate "
            + "EUR.1, or an origin declaration on the invoice")
    public record OriginProof(
            @Schema(example = "ORIGIN_DECLARATION")
            @NotNull(message = "originProof.type is required: EUR1 or ORIGIN_DECLARATION")
            ProofType type,

            @Schema(description = "The certificate number, or the invoice the declaration is on", example = "INV-2026-0412")
            @NotBlank(message = "originProof.reference is required")
            @Size(max = 64, message = "originProof.reference must be at most 64 characters")
            String reference,

            @Schema(description = "The day it was issued or made out; valid for 10 months (Article 26)")
            @NotNull(message = "originProof.issuedOn is required")
            LocalDate issuedOn,

            @Schema(description = "An origin declaration over EUR 6 000 needs the approved exporter's customs "
                    + "authorisation number (Article 24)", example = "NA/AE/0042", nullable = true)
            @Size(max = 64, message = "originProof.approvedExporterNumber must be at most 64 characters")
            String approvedExporterNumber
    ) {
    }

    public enum ProofType {
        EUR1, ORIGIN_DECLARATION
    }
}
