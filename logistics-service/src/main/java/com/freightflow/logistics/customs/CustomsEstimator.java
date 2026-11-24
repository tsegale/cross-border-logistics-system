package com.freightflow.logistics.customs;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.customs.dto.GoodsLineRequest;
import com.freightflow.logistics.customs.dto.GoodsLineRequest.OriginProof;
import com.freightflow.logistics.customs.dto.GoodsLineRequest.ProofType;
import com.freightflow.logistics.quote.FreightPricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Estimates duties and import VAT for declared goods (ADR 0035). Pure: the tariff lines, the freight and the
 * exchange rates come in, so every rule has a unit test.
 *
 * <p><b>Into the EU</b> the customs value is CIF at the EU border (UCC Articles 70 and 71(1)(e)): the goods' value
 * plus the freight to Hamburg, which is the border, that is origin handling and main carriage, shared between the
 * lines by net weight. Duty is the third-country rate on it, ad valorem plus any specific amount per 100 kg net, or
 * the EPA preference when the proof of origin holds. Import VAT (§ 11 UStG) is on the customs value, plus the duty,
 * plus the costs to the first destination in Germany (destination handling and import clearance), at 19 % or 7 %.
 *
 * <p><b>Into Namibia</b> the customs value is FOB (NamRA's valuation guidance), the goods' value alone. Duty is the
 * SACU General rate on it, or the EU / UK rate for EU goods with a valid proof. Import VAT (VAT Act, section 12(2)(a))
 * is 15 % of the FOB value plus 10 %; the open market value, which counts if it is higher, is not known to a quote.
 *
 * <p>Every amount is rounded HALF_EVEN to the customs currency's minor units, per line.
 */
public final class CustomsEstimator {

    static final BigDecimal EPA_DECLARATION_LIMIT_EUR = new BigDecimal("6000");
    static final int PROOF_VALIDITY_MONTHS = 10;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal NAMIBIAN_UPLIFT = new BigDecimal("1.10");
    private static final int SCALE = MoneyRounding.STORAGE_SCALE + 6;

    private CustomsEstimator() {
    }

    /** Converts between currencies at a mid rate: 1 {@code from} = rate {@code to}. Banking owns FX. */
    @FunctionalInterface
    public interface Rates {
        BigDecimal rate(String from, String to);
    }

    public record Goods(GoodsLineRequest request, CustomsTariffs.Line tariff) {
    }

    public record LineEstimate(int lineNumber, GoodsLineRequest request, CustomsTariffs.Line tariff,
                               BigDecimal rateToCustomsCurrency, BigDecimal customsValue, boolean preferenceApplied,
                               String preferenceNote, String dutyRateText, BigDecimal duty, BigDecimal vatBase,
                               BigDecimal vatPercent, BigDecimal importVat) {
    }

    public record Estimate(CustomsMarket market, String currency, List<LineEstimate> lines, BigDecimal duties,
                           BigDecimal importVat) {
        public BigDecimal total() {
            return duties.add(importVat);
        }
    }

    /**
     * @param freight         the whole move priced, every leg, in {@code freightCurrency}: customs counts the freight
     *                        whoever pays it
     * @param expectedImport  the day the goods are expected at customs; a proof of origin must still be valid then
     */
    public static Estimate estimate(CustomsMarket market, List<Goods> goods, FreightPricing.Charges freight,
                                    String freightCurrency, LocalDate expectedImport, Rates rates) {
        Objects.requireNonNull(market, "market");
        if (goods.isEmpty()) {
            throw new IllegalArgumentException("no goods to estimate");
        }
        String currency = market.currency();
        BigDecimal toCustoms = rates.rate(freightCurrency, currency);
        BigDecimal freightToBorder = freight.originHandling().add(freight.baseFreight()).add(freight.fuelSurcharge())
                .multiply(toCustoms);
        BigDecimal afterBorder = freight.destinationHandling().add(freight.importClearance()).multiply(toCustoms);
        BigDecimal totalWeight = goods.stream().map(g -> g.request().netWeightKg()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal originatingEur = originatingValueInEur(goods, rates);

        List<LineEstimate> lines = new ArrayList<>();
        BigDecimal duties = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (int index = 0; index < goods.size(); index++) {
            GoodsLineRequest line = goods.get(index).request();
            CustomsTariffs.Line tariff = goods.get(index).tariff();
            BigDecimal rate = rates.rate(line.customsValueCurrency(), currency);
            BigDecimal goodsValue = line.customsValue().multiply(rate);
            BigDecimal weightShare = line.netWeightKg().divide(totalWeight, SCALE, RoundingMode.HALF_EVEN);
            BigDecimal customsValue = market == CustomsMarket.EU
                    ? goodsValue.add(freightToBorder.multiply(weightShare))
                    : goodsValue;
            customsValue = round(customsValue, currency);

            Preference preference = preference(tariff, line, originatingEur, expectedImport);
            BigDecimal duty;
            String dutyText;
            if (preference.applied()) {
                duty = round(customsValue.multiply(tariff.preferencePercent()).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN),
                        currency);
                dutyText = tariff.preferencePercent().stripTrailingZeros().toPlainString() + " % ("
                        + tariff.preferenceScheme() + ")";
            } else {
                BigDecimal adValorem = customsValue.multiply(tariff.dutyPercent()).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN);
                BigDecimal specific = tariff.dutyPer100Kg() == null ? BigDecimal.ZERO
                        : tariff.dutyPer100Kg().multiply(rates.rate(tariff.dutyPer100KgCurrency(), currency))
                                .multiply(line.netWeightKg()).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN);
                duty = round(adValorem.add(specific), currency);
                dutyText = tariff.dutyText();
            }

            BigDecimal vatBase = market == CustomsMarket.EU
                    ? round(customsValue.add(duty).add(afterBorder.multiply(weightShare)), currency)
                    : round(customsValue.multiply(NAMIBIAN_UPLIFT), currency);
            BigDecimal lineVat = round(vatBase.multiply(tariff.vatPercent()).divide(HUNDRED, SCALE, RoundingMode.HALF_EVEN),
                    currency);
            lines.add(new LineEstimate(index + 1, line, tariff, rate.setScale(8, RoundingMode.HALF_EVEN), customsValue,
                    preference.applied(), preference.note(), dutyText, duty, vatBase, tariff.vatPercent(), lineVat));
            duties = duties.add(duty);
            vat = vat.add(lineVat);
        }
        return new Estimate(market, currency, List.copyOf(lines), duties, vat);
    }

    record Preference(boolean applied, String note) {
    }

    /**
     * EPA Protocol 1: a proof (Article 19), still valid on import (Article 26: ten months from issue), and for an
     * origin declaration over EUR 6 000 of originating goods an approved exporter (Article 24(1)).
     */
    static Preference preference(CustomsTariffs.Line tariff, GoodsLineRequest line, BigDecimal originatingEur,
                                 LocalDate expectedImport) {
        if (tariff.preferenceScheme() == null) {
            return new Preference(false, "no preference loaded for " + tariff.code());
        }
        if (!CustomsMarket.originMatches(tariff.preferenceOrigins(), line.originCountry())) {
            return new Preference(false, tariff.preferenceScheme() + " is for origin " + tariff.preferenceOrigins()
                    + ", not " + line.originCountry());
        }
        OriginProof proof = line.originProof();
        if (proof == null) {
            return new Preference(false, "no proof of origin: " + tariff.preferenceScheme()
                    + " needs a EUR.1 or an origin declaration (Protocol 1, Article 19)");
        }
        if (proof.issuedOn().isAfter(expectedImport)) {
            return new Preference(false, "proof of origin issued after the expected import date " + expectedImport);
        }
        if (proof.issuedOn().plusMonths(PROOF_VALIDITY_MONTHS).isBefore(expectedImport)) {
            return new Preference(false, "proof of origin of " + proof.issuedOn() + " expires before the expected "
                    + "import date " + expectedImport + " (valid 10 months, Article 26)");
        }
        if (proof.type() == ProofType.ORIGIN_DECLARATION && originatingEur.compareTo(EPA_DECLARATION_LIMIT_EUR) > 0
                && (proof.approvedExporterNumber() == null || proof.approvedExporterNumber().isBlank())) {
            return new Preference(false, "an origin declaration for more than EUR 6 000 of originating goods needs "
                    + "an approved exporter's number (Article 24(1))");
        }
        return new Preference(true, tariff.preferenceScheme() + " preference, " + (proof.type() == ProofType.EUR1
                ? "movement certificate EUR.1 " : "origin declaration on ") + proof.reference());
    }

    /** The value of the goods claiming a preference, in EUR, for Article 24's limit. */
    private static BigDecimal originatingValueInEur(List<Goods> goods, Rates rates) {
        return goods.stream()
                .filter(g -> g.request().originProof() != null && g.tariff().preferenceOrigins() != null
                        && CustomsMarket.originMatches(g.tariff().preferenceOrigins(), g.request().originCountry()))
                .map(g -> g.request().customsValue().multiply(rates.rate(g.request().customsValueCurrency(), "EUR")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal round(BigDecimal amount, String currency) {
        return MoneyRounding.normalizeCustomerAmount(amount, currency);
    }
}
