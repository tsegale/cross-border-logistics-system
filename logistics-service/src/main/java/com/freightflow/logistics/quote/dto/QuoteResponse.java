package com.freightflow.logistics.quote.dto;

import com.freightflow.logistics.customs.CustomsQuoting;
import com.freightflow.logistics.customs.CustomsTariffs;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.quote.BillingUnit;
import com.freightflow.logistics.quote.ChargeLeg;
import com.freightflow.logistics.quote.FreightQuote;
import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.PayerRole;
import com.freightflow.logistics.quote.QuotePackage;
import com.freightflow.logistics.quote.TransportMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public record QuoteResponse(
        UUID id,
        String origin,
        String destination,
        TransportMode mode,
        Incoterm incoterm,
        PayerRole payerRole,
        List<ChargeLeg> billableLegs,
        BillingUnit billingUnit,
        int packageCount,
        List<PackageSpecDto> packages,
        BigDecimal actualWeightKg,
        BigDecimal volumeCbm,
        BigDecimal volumetricWeightKg,
        BigDecimal chargeableWeightKg,
        BigDecimal chargeableUnits,
        String currency,
        BigDecimal baseFreightCharge,
        BigDecimal fuelSurcharge,
        BigDecimal originHandlingCharge,
        BigDecimal destinationHandlingCharge,
        BigDecimal importClearanceCharge,
        BigDecimal totalAmount,
        IndicativeDto indicative,
        List<QuoteLineItemDto> lineItems,
        List<String> notes,
        IncotermAllocationDto incotermAllocation,
        @Schema(description = "Estimated duties and import VAT for the declared goods (ADR 0035), shown apart from "
                + "the freight; null without goods", nullable = true)
        CustomsDto customs,
        @Schema(description = "What a booking authorises: the freight total plus the customs estimate when "
                + "FreightFlow collects it")
        BigDecimal amountDueOnBooking,
        int transitDaysMin,
        int transitDaysMax,
        Instant validUntil,
        Instant createdAt
) {

    public record QuoteLineItemDto(
            String code,
            String description,
            BigDecimal amount,
            String currency
    ) {
    }

    /**
     * The total in another currency, for information only. The customer is charged {@code totalAmount} in the quote
     * currency; banking-service fixes the rate when it authorises the payment.
     */
    public record IndicativeDto(
            String currency,
            BigDecimal totalAmount,
            String rate,
            Instant rateAsOf,
            String rateSource
    ) {
    }

    /** Who pays which leg under the Incoterm, independent of who asked for the quote. */
    public record IncotermAllocationDto(
            Incoterm incoterm,
            List<ChargeLeg> sellerLegs,
            List<ChargeLeg> buyerLegs,
            PayerRole importDutiesPaidBy
    ) {
    }

    public static final String DUTIES_EXCLUDED =
            "Import duties and taxes are not part of this quote: they depend on the customs value of the goods "
                    + "and are paid by the consignee.";
    public static final String DUTIES_AS_DISBURSEMENT =
            "Import duties and taxes are not part of this quote. Under DDP they are paid to customs on the "
                    + "shipper's behalf and billed at cost as a disbursement.";

    public static final String DUTIES_COLLECTED =
            "The estimated duties and import VAT are collected with the freight and held for customs as a "
                    + "disbursement: they are not part of the freight charge.";
    public static final String DUTIES_NOT_COLLECTED =
            "The estimated duties and import VAT are shown for information: their payer settles them with customs, "
                    + "or they are in another currency than the freight.";

    public static final String INDICATIVE_ONLY =
            "The indicative total is for information only. You are charged in the quote currency; the exchange "
                    + "rate is fixed by banking-service when the payment is authorised.";
    public static final String INDICATIVE_UNAVAILABLE =
            "An indicative total in the requested currency is unavailable right now; the quote itself is valid.";

    @Schema(description = "An estimate, not an assessment: customs assesses the real amount at import")
    public record CustomsDto(
            @Schema(example = "EU") String market,
            @Schema(example = "Estimate. Customs assesses the duties and import VAT at import; any difference is "
                    + "charged or refunded.") String label,
            String currency,
            BigDecimal estimatedDuties,
            BigDecimal estimatedImportVat,
            BigDecimal estimatedTotal,
            @Schema(description = "Who owes them: the shipper under DDP, the consignee otherwise") PayerRole paidBy,
            @Schema(description = "Whether the booking holds them with the freight; otherwise the payer settles them "
                    + "with customs directly") boolean collectedWithFreight,
            String tariffSource,
            LocalDate tariffDate,
            LocalDate tariffReviewBy,
            List<GoodsLineDto> lines
    ) {
        public static final String LABEL = "Estimate. Customs assesses the duties and import VAT at import; any "
                + "difference from what was collected is charged or refunded.";

        public static CustomsDto of(FreightQuote quote, CustomsTariffs.Source source,
                                    List<CustomsQuoting.StoredLine> lines) {
            String ccy = quote.getCustomsCurrency();
            BigDecimal duties = MoneyRounding.roundToMinorUnits(quote.getEstimatedDuties(), ccy);
            BigDecimal vat = MoneyRounding.roundToMinorUnits(quote.getEstimatedImportVat(), ccy);
            return new CustomsDto(quote.getCustomsMarket(), LABEL, ccy, duties, vat, duties.add(vat),
                    quote.getDutiesPaidBy(), Boolean.TRUE.equals(quote.getDutiesCollected()), source.name(),
                    source.referenceDate(), source.reviewBy(), lines.stream().map(line -> new GoodsLineDto(
                            line.lineNumber(), line.hsCode(), line.description(), line.originCountry(),
                            line.netWeightKg(), line.declaredValue(), line.declaredCurrency(), line.originProofType(),
                            MoneyRounding.roundToMinorUnits(line.customsValue(), ccy), line.preferenceApplied(),
                            line.preferenceNote(), line.dutyRateText(), MoneyRounding.roundToMinorUnits(line.duty(), ccy),
                            MoneyRounding.roundToMinorUnits(line.vatBase(), ccy), line.vatPercent(),
                            MoneyRounding.roundToMinorUnits(line.importVat(), ccy))).toList());
        }
    }

    public record GoodsLineDto(int line, String hsCode, String description, String originCountry,
                               BigDecimal netWeightKg, BigDecimal declaredValue, String declaredCurrency,
                               @Schema(nullable = true) String originProof,
                               @Schema(description = "CIF at the EU border into Germany, FOB into Namibia")
                               BigDecimal customsValue,
                               boolean preferenceApplied, String preferenceNote, String dutyRate, BigDecimal duty,
                               BigDecimal vatBase, BigDecimal vatPercent, BigDecimal importVat) {
    }

    public static QuoteResponse from(FreightQuote quote, List<QuotePackage> packages) {
        return from(quote, packages, null);
    }

    public static QuoteResponse from(FreightQuote quote, List<QuotePackage> packages, CustomsDto customs) {
        String ccy = quote.getCurrency();
        Incoterm incoterm = quote.getIncoterm();
        Set<ChargeLeg> legs = incoterm.billableLegs(quote.getPayerRole());
        BigDecimal baseFreight = MoneyRounding.roundToMinorUnits(quote.getBaseFreightCharge(), ccy);
        BigDecimal fuel = MoneyRounding.roundToMinorUnits(quote.getFuelSurcharge(), ccy);
        BigDecimal originHandling = MoneyRounding.roundToMinorUnits(quote.getOriginHandlingCharge(), ccy);
        BigDecimal destHandling = MoneyRounding.roundToMinorUnits(quote.getDestinationHandlingCharge(), ccy);
        BigDecimal importClearance = MoneyRounding.roundToMinorUnits(quote.getImportClearanceCharge(), ccy);
        BigDecimal total = MoneyRounding.roundToMinorUnits(quote.getTotalAmount(), ccy);

        List<QuoteLineItemDto> items = new ArrayList<>();
        if (legs.contains(ChargeLeg.ORIGIN_HANDLING)) {
            items.add(new QuoteLineItemDto("ORIGIN_HANDLING",
                    "Origin pickup, terminal handling and export clearance (" + quote.getOriginUnlocode() + ")",
                    originHandling, ccy));
        }
        if (legs.contains(ChargeLeg.MAIN_CARRIAGE)) {
            items.add(new QuoteLineItemDto("BASE_FREIGHT", "Main carriage " + quote.getMode() + " ("
                    + quote.getOriginUnlocode() + " -> " + quote.getDestinationUnlocode() + ")", baseFreight, ccy));
            items.add(new QuoteLineItemDto("FUEL_SURCHARGE", "Fuel surcharge ("
                    + quote.getSnapshotFuelSurchargePct().stripTrailingZeros().toPlainString() + "%)", fuel, ccy));
        }
        if (legs.contains(ChargeLeg.DESTINATION_HANDLING)) {
            items.add(new QuoteLineItemDto("DESTINATION_HANDLING",
                    "Destination terminal handling and delivery (" + quote.getDestinationUnlocode() + ")",
                    destHandling, ccy));
        }
        if (legs.contains(ChargeLeg.IMPORT_CLEARANCE)) {
            items.add(new QuoteLineItemDto("IMPORT_CLEARANCE",
                    "Import customs clearance, brokerage fee only (" + quote.getDestinationUnlocode() + ")",
                    importClearance, ccy));
        }

        List<PackageSpecDto> pkgDtos = packages.stream()
                .map(p -> new PackageSpecDto(
                        p.getLengthCm(),
                        p.getWidthCm(),
                        p.getHeightCm(),
                        p.getWeightKg(),
                        p.getQuantity(),
                        p.getDescription()
                ))
                .toList();

        IncotermAllocationDto allocation = new IncotermAllocationDto(
                incoterm,
                List.copyOf(incoterm.sellerLegs()),
                List.copyOf(incoterm.buyerLegs()),
                incoterm.importDutiesPaidBy()
        );
        List<String> notes = new ArrayList<>();
        if (customs == null) {
            notes.add(incoterm.importDutiesPaidBy() == PayerRole.SHIPPER ? DUTIES_AS_DISBURSEMENT : DUTIES_EXCLUDED);
        } else {
            notes.add(CustomsDto.LABEL);
            notes.add(customs.collectedWithFreight() ? DUTIES_COLLECTED : DUTIES_NOT_COLLECTED);
        }
        IndicativeDto indicative = null;
        if (quote.getIndicativeCurrency() != null && quote.getIndicativeTotalAmount() != null) {
            indicative = new IndicativeDto(
                    quote.getIndicativeCurrency(),
                    MoneyRounding.roundToMinorUnits(quote.getIndicativeTotalAmount(), quote.getIndicativeCurrency()),
                    "1 " + quote.getIndicativeCurrency() + " = " + quote.getIndicativeRate().toPlainString() + " " + ccy,
                    quote.getIndicativeRateAsOf(),
                    quote.getIndicativeRateSource());
            notes.add(INDICATIVE_ONLY);
        } else if (quote.getIndicativeCurrency() != null) {
            notes.add(INDICATIVE_UNAVAILABLE);
        }

        return new QuoteResponse(
                quote.getId(),
                quote.getOriginUnlocode(),
                quote.getDestinationUnlocode(),
                quote.getMode(),
                incoterm,
                quote.getPayerRole(),
                List.copyOf(legs),
                quote.getBillingUnit(),
                quote.getPackageCount(),
                pkgDtos,
                quote.getActualWeightKg(),
                quote.getVolumeCbm(),
                quote.getVolumetricWeightKg(),
                quote.getChargeableWeightKg(),
                quote.getChargeableUnits(),
                ccy,
                baseFreight,
                fuel,
                originHandling,
                destHandling,
                importClearance,
                total,
                indicative,
                List.copyOf(items),
                List.copyOf(notes),
                allocation,
                customs,
                customs != null && customs.collectedWithFreight() ? total.add(customs.estimatedTotal()) : total,
                quote.getTransitDaysMin(),
                quote.getTransitDaysMax(),
                quote.getValidUntil(),
                quote.getCreatedAt()
        );
    }
}
