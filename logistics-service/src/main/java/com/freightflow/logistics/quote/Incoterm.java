package com.freightflow.logistics.quote;

import java.util.List;

/**
 * Supported Incoterms 2020 rules on the Walvis Bay to Hamburg corridor (ADR 0008).
 *
 * <p>Determines which legs and customs obligations are included in the forwarder's quoted total
 * versus borne directly by the buyer/seller outside the forwarder contract:
 * <ul>
 *   <li>{@link #EXW} (Ex Works): Forwarder collects from seller's premises and handles export + main carriage
 *       (origin handling + main carriage + fuel surcharge billed on the quote; destination handling and import
 *       duties are excluded).</li>
 *   <li>{@link #FCA} (Free Carrier): Seller delivers cleared for export at named terminal ({@code NAWVB}/{@code NAWDH});
 *       forwarder bills main carriage + fuel surcharge only (origin handling, destination handling, and import
 *       duties are excluded from the forwarder's main-carriage quote).</li>
 *   <li>{@link #DAP} (Delivered at Place): Door-to-door excluding import customs/duties (origin handling +
 *       main carriage + fuel surcharge + destination handling included; import duties excluded).</li>
 *   <li>{@link #DDP} (Delivered Duty Paid): All-in door-to-door including destination import customs and duties
 *       (origin handling + main carriage + fuel surcharge + destination handling + import customs/duties included).</li>
 * </ul>
 */
public enum Incoterm {
    EXW(
            true,
            false,
            false,
            List.of("PACKAGING_AT_PREMISES"),
            List.of("ORIGIN_PICKUP_AND_EXPORT", "MAIN_CARRIAGE", "FUEL_SURCHARGE", "DESTINATION_HANDLING", "IMPORT_CUSTOMS_DUTIES")
    ),
    FCA(
            false,
            false,
            false,
            List.of("ORIGIN_EXPORT_CLEARANCE", "DELIVERY_TO_ORIGIN_TERMINAL"),
            List.of("MAIN_CARRIAGE", "FUEL_SURCHARGE", "DESTINATION_HANDLING", "IMPORT_CUSTOMS_DUTIES")
    ),
    DAP(
            true,
            true,
            false,
            List.of("ORIGIN_PICKUP_AND_EXPORT", "MAIN_CARRIAGE", "FUEL_SURCHARGE", "DESTINATION_HANDLING"),
            List.of("IMPORT_CUSTOMS_DUTIES")
    ),
    DDP(
            true,
            true,
            true,
            List.of("ORIGIN_PICKUP_AND_EXPORT", "MAIN_CARRIAGE", "FUEL_SURCHARGE", "DESTINATION_HANDLING", "IMPORT_CUSTOMS_DUTIES"),
            List.of()
    );

    private final boolean includesOriginHandling;
    private final boolean includesDestinationHandling;
    private final boolean includesImportCustomsAndDuties;
    private final List<String> sellerResponsibilities;
    private final List<String> buyerResponsibilities;

    Incoterm(
            boolean includesOriginHandling,
            boolean includesDestinationHandling,
            boolean includesImportCustomsAndDuties,
            List<String> sellerResponsibilities,
            List<String> buyerResponsibilities) {
        this.includesOriginHandling = includesOriginHandling;
        this.includesDestinationHandling = includesDestinationHandling;
        this.includesImportCustomsAndDuties = includesImportCustomsAndDuties;
        this.sellerResponsibilities = sellerResponsibilities;
        this.buyerResponsibilities = buyerResponsibilities;
    }

    public boolean includesOriginHandling() {
        return includesOriginHandling;
    }

    public boolean includesDestinationHandling() {
        return includesDestinationHandling;
    }

    public boolean includesImportCustomsAndDuties() {
        return includesImportCustomsAndDuties;
    }

    public List<String> sellerResponsibilities() {
        return sellerResponsibilities;
    }

    public List<String> buyerResponsibilities() {
        return buyerResponsibilities;
    }
}
