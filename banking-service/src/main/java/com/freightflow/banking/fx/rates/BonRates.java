package com.freightflow.banking.fx.rates;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * The Bank of Namibia's daily rates table, the second source for NAD (ADR 0034). It is the JSON that the bank's own
 * website loads ({@code services.bon.com.na/ratestable/api/rates}), not a documented API: it holds the current day's
 * table only, and its shape may change without notice. So it is a cross-check, never the price: a failure, a missing
 * field or an unreadable date means no cross-check this time, logged, and the ECB rate is judged on its other checks.
 * {@code rateEuro} is Namibia dollars per euro; {@code rateEuroDate} reads like {@code 23 Nov 2026}.
 */
public class BonRates {

    public static final String SOURCE = "bank-of-namibia";
    private static final Logger log = LoggerFactory.getLogger(BonRates.class);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH);

    private final RestClient client;
    private final CircuitBreaker breaker;

    public BonRates(RestClient client, CircuitBreakerRegistry breakers) {
        this.client = client;
        this.breaker = breakers.circuitBreaker("bank-of-namibia");
    }

    /** Namibia dollars for one euro, on the day the table is for. */
    public record NadPerEur(LocalDate valueDate, BigDecimal rate) {
    }

    public Optional<NadPerEur> euroRate() {
        JsonNode table;
        try {
            table = CircuitBreaker.decorateSupplier(breaker, () -> client.get().retrieve().body(JsonNode.class)).get();
        } catch (RuntimeException unavailable) {
            log.warn("Bank of Namibia rates unavailable, no cross-check this time: {}", unavailable.toString());
            return Optional.empty();
        }
        return parse(table);
    }

    static Optional<NadPerEur> parse(JsonNode table) {
        if (table == null) {
            return Optional.empty();
        }
        String rate = table.path("rateEuro").asString("");
        String date = table.path("rateEuroDate").asString("");
        if (rate.isBlank() || date.isBlank()) {
            log.warn("Bank of Namibia rates table has no rateEuro or rateEuroDate: its shape may have changed");
            return Optional.empty();
        }
        try {
            BigDecimal value = new BigDecimal(rate.trim());
            if (value.signum() <= 0) {
                log.warn("Bank of Namibia rateEuro {} is not positive", value);
                return Optional.empty();
            }
            return Optional.of(new NadPerEur(LocalDate.parse(date.trim(), DATE), value));
        } catch (RuntimeException unreadable) {
            log.warn("Bank of Namibia rates table unreadable: {}", unreadable.toString());
            return Optional.empty();
        }
    }
}
