package com.freightflow.logistics.customs;

import com.freightflow.logistics.customs.CustomsEstimator.Estimate;
import com.freightflow.logistics.customs.CustomsEstimator.Goods;
import com.freightflow.logistics.customs.dto.GoodsLineRequest;
import com.freightflow.logistics.customs.dto.GoodsLineRequest.OriginProof;
import com.freightflow.logistics.customs.dto.GoodsLineRequest.ProofType;
import com.freightflow.logistics.quote.FreightPricing;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Duties and import VAT with the rates loaded by V13 (TARIC 2026-11-24, SARS 2026-11-13), worked by hand in the
 * comments. The freight is a fixed whole move in EUR: origin handling 65.00, main carriage 1,900.00 plus fuel 237.50,
 * destination handling 85.00, import clearance 120.00. To the EU border: 2,202.50. After it: 205.00.
 */
class CustomsEstimatorTest {

    private static final FreightPricing.Charges FREIGHT = new FreightPricing.Charges(new BigDecimal("1900.00"),
            new BigDecimal("237.50"), new BigDecimal("65.00"), new BigDecimal("85.00"), new BigDecimal("120.00"));
    private static final LocalDate ARRIVAL = LocalDate.parse("2026-12-20");
    private static final BigDecimal EUR_NAD = new BigDecimal("19.1513");
    private static final CustomsEstimator.Rates RATES = (from, to) -> {
        if (from.equals(to)) {
            return BigDecimal.ONE;
        }
        if (from.equals("EUR") && to.equals("NAD")) {
            return EUR_NAD;
        }
        if (from.equals("NAD") && to.equals("EUR")) {
            return BigDecimal.ONE.divide(EUR_NAD, 12, java.math.RoundingMode.HALF_EVEN);
        }
        throw new IllegalArgumentException(from + "/" + to);
    };

    static final CustomsTariffs.Line HAKE = eu("0304741100", "7.5", null, "7");
    static final CustomsTariffs.Line DATES = eu("0804100099", "7.7", null, "7");
    static final CustomsTariffs.Line BEEF = eu("0201300090", "12.8", "303.40", "7");
    static final CustomsTariffs.Line FRIDGES = new CustomsTariffs.Line("84181020", "Combined refrigerator-freezers",
            new BigDecimal("25"), null, null, "Schedule 1 Part 1", "EU-SADC EPA", "EU", BigDecimal.ZERO,
            "Schedule 1 Part 1, EU / UK column", new BigDecimal("15"), "VAT Act 10 of 2000, s. 6(1)(b)");

    @Test
    void namibianHakeWithAnApprovedExportersDeclarationEntersDutyFree() {
        Estimate estimate = eu(new Goods(hake("12000", "48000.00", declaration("2026-11-01", "NA/AE/0042")), HAKE));

        // CIF 48,000.00 + 2,202.50 = 50,202.50; duty 0 %. VAT base 50,202.50 + 0 + 205.00 = 50,407.50; 7 % = 3,528.525.
        assertThat(estimate.lines().getFirst().customsValue()).isEqualByComparingTo("50202.50");
        assertThat(estimate.lines().getFirst().preferenceApplied()).isTrue();
        assertThat(estimate.duties()).isEqualByComparingTo("0.00");
        assertThat(estimate.lines().getFirst().vatBase()).isEqualByComparingTo("50407.50");
        assertThat(estimate.importVat()).as("HALF_EVEN: 3,528.525 rounds to the even cent").isEqualByComparingTo("3528.52");
        assertThat(estimate.currency()).isEqualTo("EUR");
    }

    @Test
    void theSameHakeWithoutAProofPaysTheThirdCountryDuty() {
        Estimate estimate = eu(new Goods(hake("12000", "48000.00", null), HAKE));

        // Duty 7.5 % of 50,202.50 = 3,765.1875. VAT base 50,202.50 + 3,765.19 + 205.00 = 54,172.69; 7 % = 3,792.0883.
        assertThat(estimate.lines().getFirst().preferenceApplied()).isFalse();
        assertThat(estimate.lines().getFirst().preferenceNote()).contains("no proof of origin").contains("Article 19");
        assertThat(estimate.duties()).isEqualByComparingTo("3765.19");
        assertThat(estimate.lines().getFirst().dutyRateText()).isEqualTo("7.5 %");
        assertThat(estimate.importVat()).isEqualByComparingTo("3792.09");
    }

    @Test
    void aDeclarationOverSixThousandEurosNeedsAnApprovedExporter() {
        Estimate estimate = eu(new Goods(hake("12000", "48000.00", declaration("2026-11-01", null)), HAKE));

        assertThat(estimate.lines().getFirst().preferenceApplied()).isFalse();
        assertThat(estimate.lines().getFirst().preferenceNote()).contains("EUR 6 000").contains("Article 24(1)");
        assertThat(estimate.duties()).isEqualByComparingTo("3765.19");
    }

    @Test
    void anyExporterMayDeclareUpToSixThousandEuros() {
        Estimate estimate = eu(new Goods(hake("1500", "6000.00", declaration("2026-11-01", null)), HAKE));

        assertThat(estimate.lines().getFirst().preferenceApplied()).isTrue();
        assertThat(estimate.duties()).isEqualByComparingTo("0.00");
    }

    @Test
    void aProofOlderThanTenMonthsOnArrivalDoesNotCount() {
        Estimate estimate = eu(new Goods(hake("12000", "48000.00", eur1("2026-02-19")), HAKE));

        assertThat(estimate.lines().getFirst().preferenceApplied()).isFalse();
        assertThat(estimate.lines().getFirst().preferenceNote()).contains("valid 10 months, Article 26");
    }

    @Test
    void aProofIssuedOnTheLastValidDayCounts() {
        assertThat(eu(new Goods(hake("12000", "48000.00", eur1("2026-02-20")), HAKE)).duties()).isEqualByComparingTo("0");
    }

    @Test
    void goodsOfAnotherOriginCannotClaimTheNamibianPreference() {
        GoodsLineRequest southAfrican = new GoodsLineRequest("0304741100", "Hake fillets", "ZA", 100,
                new BigDecimal("12000"), new BigDecimal("48000.00"), "EUR", eur1("2026-11-01"));

        Estimate estimate = eu(new Goods(southAfrican, HAKE));

        assertThat(estimate.lines().getFirst().preferenceNote()).contains("is for origin NA, not ZA");
        assertThat(estimate.duties()).isEqualByComparingTo("3765.19");
    }

    @Test
    void beefPaysTheAdValoremAndTheSpecificDutyPer100Kilograms() {
        GoodsLineRequest beef = new GoodsLineRequest("0201300090", "Chilled boneless beef", "NA", 40,
                new BigDecimal("1000"), new BigDecimal("8000.00"), "EUR", null);

        Estimate estimate = eu(new Goods(beef, BEEF));

        // CIF 8,000.00 + 2,202.50 = 10,202.50. Duty 12.8 % = 1,305.92, plus 303.40 x 1,000 kg / 100 = 3,034.00.
        // VAT base 10,202.50 + 4,339.92 + 205.00 = 14,747.42; 7 % = 1,032.3194.
        assertThat(estimate.duties()).isEqualByComparingTo("4339.92");
        assertThat(estimate.lines().getFirst().dutyRateText()).isEqualTo("12.8 % + 303.4 EUR / 100 kg");
        assertThat(estimate.importVat()).isEqualByComparingTo("1032.32");
    }

    @Test
    void freightIsSharedBetweenTheLinesByNetWeight() {
        GoodsLineRequest dates = new GoodsLineRequest("0804100099", "Dried dates", "NA", 300, new BigDecimal("3000"),
                new BigDecimal("6000.00"), "EUR", null);

        Estimate estimate = eu(new Goods(hake("9000", "36000.00", null), HAKE), new Goods(dates, DATES));

        // Three quarters of 2,202.50 is 1,651.875, a quarter 550.625.
        assertThat(estimate.lines().get(0).customsValue()).isEqualByComparingTo("37651.88");
        assertThat(estimate.lines().get(1).customsValue()).as("HALF_EVEN: 6,550.625").isEqualByComparingTo("6550.62");
        // 7.5 % of 37,651.88 = 2,823.891; 7.7 % of 6,550.62 = 504.39774.
        assertThat(estimate.lines().get(0).duty()).isEqualByComparingTo("2823.89");
        assertThat(estimate.lines().get(1).duty()).isEqualByComparingTo("504.40");
        assertThat(estimate.duties()).isEqualByComparingTo("3328.29");
    }

    @Test
    void germanFridgesIntoNamibiaAreFobWithTheEuRateAndVatOnFobPlusTenPercent() {
        GoodsLineRequest fridges = new GoodsLineRequest("84181020", "Fridge-freezers, 350 l", "DE", 40,
                new BigDecimal("2400"), new BigDecimal("10000.00"), "EUR", eur1("2026-11-10"));

        Estimate estimate = sacu(new Goods(fridges, FRIDGES));

        // FOB 10,000.00 EUR x 19.1513 = 191,513.00 NAD; no freight in the SACU customs value. EU / UK rate: free.
        // VAT base 191,513.00 x 1.10 = 210,664.30; 15 % = 31,599.645.
        assertThat(estimate.currency()).isEqualTo("NAD");
        assertThat(estimate.lines().getFirst().customsValue()).isEqualByComparingTo("191513.00");
        assertThat(estimate.duties()).isEqualByComparingTo("0.00");
        assertThat(estimate.lines().getFirst().vatBase()).isEqualByComparingTo("210664.30");
        assertThat(estimate.importVat()).isEqualByComparingTo("31599.64");
    }

    @Test
    void theSameFridgesWithoutAProofPayTheGeneralRateButNoVatOnTheDuty() {
        GoodsLineRequest fridges = new GoodsLineRequest("84181020", "Fridge-freezers, 350 l", "DE", 40,
                new BigDecimal("2400"), new BigDecimal("10000.00"), "EUR", null);

        Estimate estimate = sacu(new Goods(fridges, FRIDGES));

        // 25 % of 191,513.00 = 47,878.25. Namibian import VAT is not charged on the duty (section 12(2)(a)).
        assertThat(estimate.duties()).isEqualByComparingTo("47878.25");
        assertThat(estimate.importVat()).isEqualByComparingTo("31599.64");
    }

    private static Estimate eu(Goods... goods) {
        return CustomsEstimator.estimate(CustomsMarket.EU, List.of(goods), FREIGHT, "EUR", ARRIVAL, RATES);
    }

    private static Estimate sacu(Goods... goods) {
        return CustomsEstimator.estimate(CustomsMarket.SACU, List.of(goods), FREIGHT, "EUR", ARRIVAL, RATES);
    }

    private static GoodsLineRequest hake(String kg, String value, OriginProof proof) {
        return new GoodsLineRequest("0304741100", "Frozen Cape hake fillets", "NA", 1200, new BigDecimal(kg),
                new BigDecimal(value), "EUR", proof);
    }

    private static OriginProof declaration(String issued, String approvedExporter) {
        return new OriginProof(ProofType.ORIGIN_DECLARATION, "INV-2026-0412", LocalDate.parse(issued), approvedExporter);
    }

    private static OriginProof eur1(String issued) {
        return new OriginProof(ProofType.EUR1, "NA 0012345", LocalDate.parse(issued), null);
    }

    private static CustomsTariffs.Line eu(String code, String duty, String per100Kg, String vat) {
        return new CustomsTariffs.Line(code, code, new BigDecimal(duty), per100Kg == null ? null : new BigDecimal(per100Kg),
                per100Kg == null ? null : "EUR", "R2204/99", "EU-SADC EPA", "NA", BigDecimal.ZERO, "D1623/16",
                new BigDecimal(vat), "§ 12 UStG");
    }
}
