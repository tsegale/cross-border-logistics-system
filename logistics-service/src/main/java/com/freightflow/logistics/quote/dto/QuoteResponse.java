package com.freightflow.logistics.quote.dto;

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
        List<QuoteLineItemDto> lineItems,
        List<String> notes,
        IncotermAllocationDto incotermAllocation,
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

    public static QuoteResponse from(FreightQuote quote, List<QuotePackage> packages) {
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
        List<String> notes = List.of(incoterm.importDutiesPaidBy() == PayerRole.SHIPPER
                ? DUTIES_AS_DISBURSEMENT
                : DUTIES_EXCLUDED);

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
                List.copyOf(items),
                notes,
                allocation,
                quote.getTransitDaysMin(),
                quote.getTransitDaysMax(),
                quote.getValidUntil(),
                quote.getCreatedAt()
        );
    }
}
