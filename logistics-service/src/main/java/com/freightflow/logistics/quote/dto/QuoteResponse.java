package com.freightflow.logistics.quote.dto;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.quote.BillingUnit;
import com.freightflow.logistics.quote.FreightQuote;
import com.freightflow.logistics.quote.Incoterm;
import com.freightflow.logistics.quote.QuotePackage;
import com.freightflow.logistics.quote.TransportMode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public record QuoteResponse(
        UUID id,
        String origin,
        String destination,
        TransportMode mode,
        Incoterm incoterm,
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
        BigDecimal importCustomsDutyCharge,
        BigDecimal totalAmount,
        List<QuoteLineItemDto> lineItems,
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

    public record IncotermAllocationDto(
            Incoterm incoterm,
            List<String> sellerResponsibilities,
            List<String> buyerResponsibilities
    ) {
    }

    public static QuoteResponse from(FreightQuote quote, List<QuotePackage> packages) {
        String ccy = quote.getCurrency();
        BigDecimal baseFreight = MoneyRounding.roundToMinorUnits(quote.getBaseFreightCharge(), ccy);
        BigDecimal fuel = MoneyRounding.roundToMinorUnits(quote.getFuelSurcharge(), ccy);
        BigDecimal originHandling = MoneyRounding.roundToMinorUnits(quote.getOriginHandlingCharge(), ccy);
        BigDecimal destHandling = MoneyRounding.roundToMinorUnits(quote.getDestinationHandlingCharge(), ccy);
        BigDecimal importDuty = MoneyRounding.roundToMinorUnits(quote.getImportCustomsDutyCharge(), ccy);
        BigDecimal total = MoneyRounding.roundToMinorUnits(quote.getTotalAmount(), ccy);

        List<QuoteLineItemDto> items = new ArrayList<>();
        if (originHandling.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new QuoteLineItemDto("ORIGIN_HANDLING", "Origin pickup and export clearance (" + quote.getOriginUnlocode() + ")", originHandling, ccy));
        }
        items.add(new QuoteLineItemDto("BASE_FREIGHT", "Main carriage " + quote.getMode() + " (" + quote.getOriginUnlocode() + " -> " + quote.getDestinationUnlocode() + ")", baseFreight, ccy));
        items.add(new QuoteLineItemDto("FUEL_SURCHARGE", "Fuel surcharge (" + quote.getSnapshotFuelSurchargePct().stripTrailingZeros().toPlainString() + "%)", fuel, ccy));
        if (destHandling.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new QuoteLineItemDto("DESTINATION_HANDLING", "Destination terminal handling and delivery (" + quote.getDestinationUnlocode() + ")", destHandling, ccy));
        }
        if (importDuty.compareTo(BigDecimal.ZERO) > 0) {
            items.add(new QuoteLineItemDto("IMPORT_CUSTOMS_DUTIES", "Import customs clearance and duties (" + quote.getDestinationUnlocode() + ")", importDuty, ccy));
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

        Incoterm incoterm = quote.getIncoterms();
        IncotermAllocationDto allocation = new IncotermAllocationDto(
                incoterm,
                incoterm.sellerResponsibilities(),
                incoterm.buyerResponsibilities()
        );

        return new QuoteResponse(
                quote.getId(),
                quote.getOriginUnlocode(),
                quote.getDestinationUnlocode(),
                quote.getMode(),
                incoterm,
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
                importDuty,
                total,
                List.copyOf(items),
                allocation,
                quote.getTransitDaysMin(),
                quote.getTransitDaysMax(),
                quote.getValidUntil(),
                quote.getCreatedAt()
        );
    }
}
