package com.freightflow.logistics.customs;

import com.freightflow.logistics.banking.BankingApi;
import com.freightflow.logistics.banking.BankingCalls;
import com.freightflow.logistics.banking.FxRates;
import com.freightflow.logistics.customs.CustomsEstimator.Estimate;
import com.freightflow.logistics.customs.CustomsEstimator.LineEstimate;
import com.freightflow.logistics.customs.dto.GoodsLineRequest;
import com.freightflow.logistics.quote.FreightPricing;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The customs part of a quote (ADR 0035): which market the goods enter, the tariff subset that applies, every line
 * checked against it, and the estimate, with values converted at banking's mid rates (banking owns FX).
 */
@Service
public class CustomsQuoting {

    private final CustomsTariffs tariffs;
    private final BankingApi banking;
    private final BankingCalls calls;
    private final JdbcTemplate jdbcTemplate;

    public CustomsQuoting(CustomsTariffs tariffs, BankingApi banking, BankingCalls calls, JdbcTemplate jdbcTemplate) {
        this.tariffs = tariffs;
        this.banking = banking;
        this.calls = calls;
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Quoted(CustomsTariffs.Source source, Estimate estimate) {
    }

    public Quoted estimate(String destinationCountry, List<GoodsLineRequest> goods, FreightPricing.Charges wholeMove,
                           String freightCurrency, LocalDate today, LocalDate expectedImport) {
        CustomsMarket market = CustomsMarket.importingInto(destinationCountry)
                .orElseThrow(() -> new CustomsExceptions.CustomsMarketUnsupportedException(destinationCountry));
        CustomsTariffs.Source source = tariffs.current(market, today)
                .orElseThrow(() -> new IllegalStateException("No " + market + " tariff subset is loaded"));
        List<CustomsEstimator.Goods> lines = new ArrayList<>();
        for (int index = 0; index < goods.size(); index++) {
            GoodsLineRequest line = goods.get(index);
            if (line.hsCode().length() != market.codeLength()) {
                throw new CustomsExceptions.InvalidHsCodeException(index + 1, line.hsCode(), market);
            }
            CustomsTariffs.Line tariff = tariffs.line(source.id(), line.hsCode())
                    .orElseThrow(() -> new CustomsExceptions.UnknownTariffCodeException(line.hsCode(), market,
                            source.referenceDate()));
            lines.add(new CustomsEstimator.Goods(line, tariff));
        }
        Map<String, BigDecimal> cache = new HashMap<>();
        Estimate estimate = CustomsEstimator.estimate(market, lines, wholeMove, freightCurrency, expectedImport,
                (from, to) -> from.equals(to) ? BigDecimal.ONE
                        : cache.computeIfAbsent(from + "/" + to, pair -> midRate(from, to)));
        return new Quoted(source, estimate);
    }

    /** banking's mid rate: an estimate is not a conversion, so no spread. */
    private BigDecimal midRate(String from, String to) {
        try {
            FxRates rates = calls.fxRate(() -> banking.fxRates(from));
            Optional<FxRates.Rate> rate = rates == null ? Optional.empty() : rates.to(to);
            return rate.map(FxRates.Rate::midRate).filter(mid -> mid.signum() > 0)
                    .orElseThrow(() -> new CustomsExceptions.CustomsConversionUnavailableException(from, to));
        } catch (RestClientException | CallNotPermittedException | BulkheadFullException
                 | org.springframework.security.oauth2.core.OAuth2AuthorizationException ex) {
            throw new CustomsExceptions.CustomsConversionUnavailableException(from, to);
        }
    }

    public void save(UUID quoteId, Estimate estimate) {
        for (LineEstimate line : estimate.lines()) {
            GoodsLineRequest goods = line.request();
            GoodsLineRequest.OriginProof proof = goods.originProof();
            jdbcTemplate.update("""
                    INSERT INTO quote_goods_lines (quote_id, line_number, hs_code, description, origin_country, quantity,
                        net_weight_kg, declared_value, declared_currency, origin_proof_type, origin_proof_reference,
                        origin_proof_issued_on, approved_exporter_number, rate_to_customs_currency, customs_value,
                        preference_applied, preference_note, duty_rate_text, duty, vat_base, vat_percent, import_vat)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, quoteId, line.lineNumber(), goods.hsCode(), goods.description().trim(),
                    goods.originCountry(), goods.quantity(), goods.netWeightKg(), goods.customsValue(),
                    goods.customsValueCurrency(), proof == null ? null : proof.type().name(),
                    proof == null ? null : proof.reference().trim(),
                    proof == null ? null : Date.valueOf(proof.issuedOn()),
                    proof == null || proof.approvedExporterNumber() == null ? null : proof.approvedExporterNumber().trim(),
                    line.rateToCustomsCurrency(), line.customsValue(), line.preferenceApplied(),
                    truncate(line.preferenceNote(), 300), line.dutyRateText(), line.duty(), line.vatBase(),
                    line.vatPercent(), line.importVat());
        }
    }

    /** A quote's stored goods lines, for reading it back. */
    public List<StoredLine> lines(UUID quoteId) {
        return jdbcTemplate.query("""
                SELECT line_number, hs_code, description, origin_country, quantity, net_weight_kg, declared_value,
                       declared_currency, origin_proof_type, origin_proof_reference, customs_value, preference_applied,
                       preference_note, duty_rate_text, duty, vat_base, vat_percent, import_vat
                FROM quote_goods_lines WHERE quote_id = ? ORDER BY line_number
                """, (rs, row) -> new StoredLine(rs.getInt("line_number"), rs.getString("hs_code"),
                rs.getString("description"), rs.getString("origin_country"), rs.getInt("quantity"),
                rs.getBigDecimal("net_weight_kg"), rs.getBigDecimal("declared_value"),
                rs.getString("declared_currency"), rs.getString("origin_proof_type"),
                rs.getString("origin_proof_reference"), rs.getBigDecimal("customs_value"),
                rs.getBoolean("preference_applied"), rs.getString("preference_note"), rs.getString("duty_rate_text"),
                rs.getBigDecimal("duty"), rs.getBigDecimal("vat_base"), rs.getBigDecimal("vat_percent"),
                rs.getBigDecimal("import_vat")), quoteId);
    }

    public Optional<CustomsTariffs.Source> source(UUID sourceId) {
        return jdbcTemplate.query("""
                SELECT id, market, name, reference_date, retrieved_on, review_by FROM customs_tariff_sources WHERE id = ?
                """, (rs, row) -> new CustomsTariffs.Source(rs.getObject("id", UUID.class),
                CustomsMarket.valueOf(rs.getString("market").toUpperCase(Locale.ROOT)), rs.getString("name"),
                rs.getObject("reference_date", LocalDate.class), rs.getObject("retrieved_on", LocalDate.class),
                rs.getObject("review_by", LocalDate.class)), sourceId).stream().findFirst();
    }

    public record StoredLine(int lineNumber, String hsCode, String description, String originCountry, int quantity,
                             BigDecimal netWeightKg, BigDecimal declaredValue, String declaredCurrency,
                             String originProofType, String originProofReference, BigDecimal customsValue,
                             boolean preferenceApplied, String preferenceNote, String dutyRateText, BigDecimal duty,
                             BigDecimal vatBase, BigDecimal vatPercent, BigDecimal importVat) {
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
