package com.freightflow.logistics.quote;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * Incoterms 2020 rules used on the corridor (ADR 0008). An Incoterm says who pays for which part of the move;
 * it does not say what the forwarder sells. The billable legs of a quote are the legs that the quoting party
 * ({@link PayerRole}) pays under the rule:
 * <pre>
 *            ORIGIN_HANDLING  MAIN_CARRIAGE  DESTINATION_HANDLING  IMPORT_CLEARANCE  import duties
 *   EXW      buyer            buyer          buyer                 buyer             buyer
 *   FCA      seller           buyer          buyer                 buyer             buyer
 *   DAP      seller           seller         seller                buyer             buyer
 *   DDP      seller           seller         seller                seller            seller (disbursement)
 * </pre>
 * FCA is read with the named place at the origin terminal, so delivery to the carrier and export clearance are
 * the seller's. DAP is read with the destination terminal charges inside the seller's contract of carriage.
 */
public enum Incoterm {
    EXW(EnumSet.noneOf(ChargeLeg.class)),
    FCA(EnumSet.of(ChargeLeg.ORIGIN_HANDLING)),
    DAP(EnumSet.of(ChargeLeg.ORIGIN_HANDLING, ChargeLeg.MAIN_CARRIAGE, ChargeLeg.DESTINATION_HANDLING)),
    DDP(EnumSet.allOf(ChargeLeg.class));

    private final Set<ChargeLeg> sellerLegs;
    private final Set<ChargeLeg> buyerLegs;

    Incoterm(EnumSet<ChargeLeg> sellerLegs) {
        this.sellerLegs = Collections.unmodifiableSet(sellerLegs);
        this.buyerLegs = Collections.unmodifiableSet(EnumSet.complementOf(sellerLegs));
    }

    public Set<ChargeLeg> sellerLegs() {
        return sellerLegs;
    }

    public Set<ChargeLeg> buyerLegs() {
        return buyerLegs;
    }

    /** The legs the forwarder bills when {@code payer} asks for the quote. */
    public Set<ChargeLeg> billableLegs(PayerRole payer) {
        return payer == PayerRole.SHIPPER ? sellerLegs : buyerLegs;
    }

    /** Duties and taxes follow import clearance: the buyer's, except under DDP. */
    public PayerRole importDutiesPaidBy() {
        return sellerLegs.contains(ChargeLeg.IMPORT_CLEARANCE) ? PayerRole.SHIPPER : PayerRole.CONSIGNEE;
    }
}
