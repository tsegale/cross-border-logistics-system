package com.freightflow.logistics.quote;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static com.freightflow.logistics.quote.ChargeLeg.DESTINATION_HANDLING;
import static com.freightflow.logistics.quote.ChargeLeg.IMPORT_CLEARANCE;
import static com.freightflow.logistics.quote.ChargeLeg.MAIN_CARRIAGE;
import static com.freightflow.logistics.quote.ChargeLeg.ORIGIN_HANDLING;
import static com.freightflow.logistics.quote.PayerRole.CONSIGNEE;
import static com.freightflow.logistics.quote.PayerRole.SHIPPER;
import static org.assertj.core.api.Assertions.assertThat;

/** One test per Incoterm x payer role: the billable legs are exactly the legs that party pays (ADR 0008). */
class IncotermBillableLegsTest {

    @Test
    void exwShipperPaysNothing() {
        assertThat(Incoterm.EXW.billableLegs(SHIPPER)).isEmpty();
    }

    @Test
    void exwConsigneePaysEveryLegIncludingDestinationHandling() {
        assertThat(Incoterm.EXW.billableLegs(CONSIGNEE))
                .containsExactly(ORIGIN_HANDLING, MAIN_CARRIAGE, DESTINATION_HANDLING, IMPORT_CLEARANCE);
    }

    @Test
    void fcaShipperPaysOriginHandlingAndExportClearance() {
        assertThat(Incoterm.FCA.billableLegs(SHIPPER)).containsExactly(ORIGIN_HANDLING);
    }

    @Test
    void fcaConsigneePaysMainCarriageOnwards() {
        assertThat(Incoterm.FCA.billableLegs(CONSIGNEE))
                .containsExactly(MAIN_CARRIAGE, DESTINATION_HANDLING, IMPORT_CLEARANCE);
    }

    @Test
    void dapShipperPaysUpToDeliveryAtTheNamedPlace() {
        assertThat(Incoterm.DAP.billableLegs(SHIPPER))
                .containsExactly(ORIGIN_HANDLING, MAIN_CARRIAGE, DESTINATION_HANDLING);
    }

    @Test
    void dapConsigneePaysImportClearance() {
        assertThat(Incoterm.DAP.billableLegs(CONSIGNEE)).containsExactly(IMPORT_CLEARANCE);
    }

    @Test
    void ddpShipperPaysEveryLeg() {
        assertThat(Incoterm.DDP.billableLegs(SHIPPER))
                .containsExactly(ORIGIN_HANDLING, MAIN_CARRIAGE, DESTINATION_HANDLING, IMPORT_CLEARANCE);
    }

    @Test
    void ddpConsigneePaysNothing() {
        assertThat(Incoterm.DDP.billableLegs(CONSIGNEE)).isEmpty();
    }

    @Test
    void sellerAndBuyerLegsPartitionTheMoveUnderEveryRule() {
        for (Incoterm incoterm : Incoterm.values()) {
            Set<ChargeLeg> union = EnumSet.noneOf(ChargeLeg.class);
            union.addAll(incoterm.sellerLegs());
            union.addAll(incoterm.buyerLegs());
            assertThat(union).containsExactly(ChargeLeg.values());
            assertThat(incoterm.sellerLegs().size() + incoterm.buyerLegs().size()).isEqualTo(ChargeLeg.values().length);
        }
    }

    @Test
    void importDutiesAreTheSellersOnlyUnderDdp() {
        assertThat(Incoterm.DDP.importDutiesPaidBy()).isEqualTo(SHIPPER);
        assertThat(Incoterm.DAP.importDutiesPaidBy()).isEqualTo(CONSIGNEE);
        assertThat(Incoterm.FCA.importDutiesPaidBy()).isEqualTo(CONSIGNEE);
        assertThat(Incoterm.EXW.importDutiesPaidBy()).isEqualTo(CONSIGNEE);
    }
}
