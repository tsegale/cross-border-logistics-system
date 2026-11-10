package com.freightflow.banking.fx;

import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.ExchangeRateProvider.RateQuote;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FxQuoteUnitTest {

    private static BankingProperties defaultProperties() {
        return new BankingProperties(
                List.of("NAD", "ZAR", "EUR"),
                "pessimistic",
                new BankingProperties.Idempotency(Duration.ofHours(24), Duration.ofMinutes(15), Duration.ofSeconds(5)),
                new BankingProperties.Fx(
                        Duration.ofSeconds(60),
                        new BigDecimal("0.0050"),
                        Map.of(
                                "NAD", Map.of("ZAR", new BigDecimal("1.00000000"), "EUR", new BigDecimal("0.05000000")),
                                "ZAR", Map.of("NAD", new BigDecimal("1.00000000"), "EUR", new BigDecimal("0.05000000")),
                                "EUR", Map.of("NAD", new BigDecimal("20.00000000"), "ZAR", new BigDecimal("20.00000000"))
                        ),
                        Map.of("NAD-ZAR", new BigDecimal("0.0000"), "ZAR-NAD", new BigDecimal("0.0000"))
                ),
                null
        );
    }

    @Test
    void respectsCmaOneToOnePegBetweenNadAndZar() {
        StaticConfiguredExchangeRateProvider provider = new StaticConfiguredExchangeRateProvider(defaultProperties());

        RateQuote nadToZar = provider.lookupRate("NAD", "ZAR");
        assertThat(nadToZar.midRate()).isEqualByComparingTo("1.00000000");
        assertThat(nadToZar.spread()).isEqualByComparingTo("0.000000");
        assertThat(nadToZar.effectiveRate()).isEqualByComparingTo("1.00000000");

        RateQuote zarToNad = provider.lookupRate("ZAR", "NAD");
        assertThat(zarToNad.effectiveRate()).isEqualByComparingTo("1.00000000");
    }

    @Test
    void appliesSpreadToNonPeggedPairs() {
        StaticConfiguredExchangeRateProvider provider = new StaticConfiguredExchangeRateProvider(defaultProperties());

        RateQuote nadToEur = provider.lookupRate("NAD", "EUR");
        assertThat(nadToEur.midRate()).isEqualByComparingTo("0.05000000");
        assertThat(nadToEur.spread()).isEqualByComparingTo("0.005000");
        // 0.05 * (1 - 0.005) = 0.04975000
        assertThat(nadToEur.effectiveRate()).isEqualByComparingTo("0.04975000");

        RateQuote eurToNad = provider.lookupRate("EUR", "NAD");
        assertThat(eurToNad.midRate()).isEqualByComparingTo("20.00000000");
        // 20 * (1 - 0.005) = 19.90000000
        assertThat(eurToNad.effectiveRate()).isEqualByComparingTo("19.90000000");
    }

    @Test
    void rejectsSameCurrencyAndViolatedCmaPeg() {
        StaticConfiguredExchangeRateProvider provider = new StaticConfiguredExchangeRateProvider(defaultProperties());
        assertThatThrownBy(() -> provider.lookupRate("NAD", "NAD"))
                .isInstanceOf(InvalidFxQuoteRequestException.class);

        BankingProperties brokenPeg = new BankingProperties(
                List.of("NAD", "ZAR", "EUR"),
                "pessimistic",
                new BankingProperties.Idempotency(Duration.ofHours(24), Duration.ofMinutes(15), Duration.ofSeconds(5)),
                new BankingProperties.Fx(
                        Duration.ofSeconds(60),
                        new BigDecimal("0.0050"),
                        Map.of("NAD", Map.of("ZAR", new BigDecimal("1.02000000"))),
                        Map.of()
                ),
                null
        );
        StaticConfiguredExchangeRateProvider brokenProvider = new StaticConfiguredExchangeRateProvider(brokenPeg);
        assertThatThrownBy(() -> brokenProvider.lookupRate("NAD", "ZAR"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CMA peg");
    }

    @Test
    void quoteConsumeEnforcesSingleUseAndExpiry() {
        Instant t0 = Instant.parse("2026-04-19T12:00:00Z");
        Instant expires = t0.plusSeconds(60);
        FxQuote quote = FxQuote.create(
                "NAD",
                "EUR",
                new BigDecimal("5000.00"),
                new BigDecimal("248.75"),
                new BigDecimal("0.05000000"),
                new BigDecimal("0.005000"),
                new BigDecimal("0.04975000"),
                t0,
                expires
        );

        assertThatThrownBy(() -> quote.consume(expires))
                .isInstanceOf(FxQuoteUnavailableException.class)
                .satisfies(ex -> assertThat(((FxQuoteUnavailableException) ex).getSlug()).isEqualTo("fx-quote-expired"));

        quote.consume(t0.plusSeconds(10));
        assertThat(quote.getUsedAt()).isEqualTo(t0.plusSeconds(10));

        assertThatThrownBy(() -> quote.consume(t0.plusSeconds(15)))
                .isInstanceOf(FxQuoteUnavailableException.class)
                .satisfies(ex -> assertThat(((FxQuoteUnavailableException) ex).getSlug()).isEqualTo("fx-quote-already-used"));
    }
}
