package com.freightflow.logistics.customs;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The loaded tariff subsets (V13 and later migrations, ADR 0035). Read only: a new subset is a new migration. */
@Repository
public class CustomsTariffs {

    private final JdbcTemplate jdbcTemplate;

    public CustomsTariffs(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record Source(UUID id, CustomsMarket market, String name, LocalDate referenceDate, LocalDate retrievedOn,
                         LocalDate reviewBy) {
    }

    public record Line(String code, String description, BigDecimal dutyPercent, BigDecimal dutyPer100Kg,
                       String dutyPer100KgCurrency, String dutyLegalBase, String preferenceScheme,
                       String preferenceOrigins, BigDecimal preferencePercent, String preferenceLegalBase,
                       BigDecimal vatPercent, String vatLegalBase) {

        /** How the tariff writes the general duty: "7.5 %", or "12.8 % + 303.40 EUR / 100 kg". */
        public String dutyText() {
            String percent = dutyPercent.stripTrailingZeros().toPlainString() + " %";
            return dutyPer100Kg == null ? percent
                    : percent + " + " + dutyPer100Kg.stripTrailingZeros().toPlainString() + " " + dutyPer100KgCurrency
                    + " / 100 kg";
        }
    }

    /** The newest subset of the market with a reference date on or before {@code on}. */
    public Optional<Source> current(CustomsMarket market, LocalDate on) {
        List<Source> sources = jdbcTemplate.query("""
                SELECT id, market, name, reference_date, retrieved_on, review_by FROM customs_tariff_sources
                WHERE market = ? AND reference_date <= ? ORDER BY reference_date DESC LIMIT 1
                """, (rs, row) -> new Source(rs.getObject("id", UUID.class), CustomsMarket.valueOf(rs.getString("market")),
                rs.getString("name"), rs.getObject("reference_date", LocalDate.class),
                rs.getObject("retrieved_on", LocalDate.class), rs.getObject("review_by", LocalDate.class)),
                market.name(), Date.valueOf(on));
        return sources.stream().findFirst();
    }

    public Optional<Line> line(UUID sourceId, String code) {
        return jdbcTemplate.query("""
                SELECT code, description, duty_percent, duty_per_100kg, duty_per_100kg_currency, duty_legal_base,
                       preference_scheme, preference_origins, preference_percent, preference_legal_base,
                       vat_percent, vat_legal_base
                FROM customs_tariff_lines WHERE source_id = ? AND code = ?
                """, (rs, row) -> new Line(rs.getString("code"), rs.getString("description"),
                rs.getBigDecimal("duty_percent"), rs.getBigDecimal("duty_per_100kg"),
                rs.getString("duty_per_100kg_currency"), rs.getString("duty_legal_base"),
                rs.getString("preference_scheme"), rs.getString("preference_origins"),
                rs.getBigDecimal("preference_percent"), rs.getString("preference_legal_base"),
                rs.getBigDecimal("vat_percent"), rs.getString("vat_legal_base")), sourceId, code)
                .stream().findFirst();
    }

    /** Days until the market's current subset must be reviewed; negative once it is overdue. For the gauge. */
    public double daysToReview(CustomsMarket market, LocalDate today) {
        return current(market, today)
                .map(source -> (double) (source.reviewBy().toEpochDay() - today.toEpochDay()))
                .orElse(Double.NEGATIVE_INFINITY);
    }
}
