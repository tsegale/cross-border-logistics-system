package com.freightflow.banking.fx.rates;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;

import static com.freightflow.banking.fx.rates.TargetCalendarTest.read;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The two rate sources against responses recorded from the real APIs on 2026-11-24 (src/test/resources/ecb and bon):
 * the last five ZAR observations, a range over Christmas 2025, a range over Easter 2026, the ECB's 404 for a series
 * without observations, and the Bank of Namibia's table. No test calls a live API.
 */
class RateSourcesTest {

    private static final WireMockServer SOURCE = new WireMockServer(wireMockConfig().dynamicPort());
    private static final String ZAR_SERIES = "/service/data/EXR/D.ZAR.EUR.SP00.A";

    @BeforeAll
    static void start() {
        SOURCE.start();
    }

    @AfterAll
    static void stop() {
        SOURCE.stop();
    }

    @BeforeEach
    void reset() {
        SOURCE.resetAll();
    }

    @Test
    void readsTheLatestObservationsOldestFirstAndAsksForCsv() throws IOException {
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).willReturn(csv("ecb/zar-latest-5.csv")));

        List<EcbRates.Observation> observations = ecb(CircuitBreakerRegistry.ofDefaults()).latest("ZAR", 5);

        assertThat(observations).extracting(EcbRates.Observation::valueDate).containsExactly(
                LocalDate.parse("2026-11-17"), LocalDate.parse("2026-11-18"), LocalDate.parse("2026-11-19"),
                LocalDate.parse("2026-11-20"), LocalDate.parse("2026-11-23"));
        assertThat(observations.getLast().rate()).isEqualByComparingTo("19.1513");
        SOURCE.verify(getRequestedFor(urlPathEqualTo(ZAR_SERIES))
                .withQueryParam("lastNObservations", equalTo("5"))
                .withQueryParam("format", equalTo("csvdata"))
                .withQueryParam("detail", equalTo("dataonly")));
    }

    @Test
    void aWeekendAndTheChristmasHolidaysHaveNoObservations() throws IOException {
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).willReturn(csv("ecb/zar-christmas-2025.csv")));

        List<LocalDate> days = ecb(CircuitBreakerRegistry.ofDefaults()).latest("ZAR", 10).stream()
                .map(EcbRates.Observation::valueDate).toList();

        assertThat(days).contains(LocalDate.parse("2025-12-24"), LocalDate.parse("2025-12-29"))
                .doesNotContain(LocalDate.parse("2025-12-25"), LocalDate.parse("2025-12-26"),
                        LocalDate.parse("2025-12-27"), LocalDate.parse("2026-01-01"));
    }

    @Test
    void aSeriesWithoutObservationsIsEmptyNotAnError() throws IOException {
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).willReturn(aResponse().withStatus(404)
                .withHeader("Content-Type", "application/json").withBody(read("ecb/unknown-currency-404.body"))));

        assertThat(ecb(CircuitBreakerRegistry.ofDefaults()).latest("ZAR", 5)).isEmpty();
    }

    @Test
    void aServerErrorIsRetriedWithinTheBudget() throws IOException {
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).inScenario("flaky").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).inScenario("flaky").whenScenarioStateIs("recovered")
                .willReturn(csv("ecb/zar-latest-5.csv")));

        assertThat(ecb(CircuitBreakerRegistry.ofDefaults()).latest("ZAR", 5)).hasSize(5);
        SOURCE.verify(2, getRequestedFor(urlPathEqualTo(ZAR_SERIES)));
    }

    @Test
    void aPersistentServerErrorFailsAfterTheRetriesAndOpensTheBreaker() {
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).willReturn(aResponse().withStatus(500)));
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowSize(5).minimumNumberOfCalls(3).failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofMinutes(5)).build());
        EcbRates ecb = ecb(breakers);

        assertThatThrownBy(() -> ecb.latest("ZAR", 5)).isInstanceOf(HttpServerErrorException.class);
        SOURCE.verify(3, getRequestedFor(urlPathEqualTo(ZAR_SERIES)));
        // Three failed attempts: the breaker is open and the next refresh fails without calling the ECB.
        assertThatThrownBy(() -> ecb.latest("ZAR", 5)).isInstanceOf(CallNotPermittedException.class);
        SOURCE.verify(3, getRequestedFor(urlPathEqualTo(ZAR_SERIES)));
    }

    @Test
    void anAnswerThatIsNotTheDocumentedCsvIsNotRetried() {
        SOURCE.stubFor(get(urlPathEqualTo(ZAR_SERIES)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/html").withBody("<html>maintenance</html>")));

        assertThatThrownBy(() -> ecb(CircuitBreakerRegistry.ofDefaults()).latest("ZAR", 5))
                .isInstanceOf(EcbRates.EcbResponseException.class);
        SOURCE.verify(1, getRequestedFor(urlPathEqualTo(ZAR_SERIES)));
    }

    @Test
    void aRowForAnotherSeriesIsRefused() {
        assertThatThrownBy(() -> EcbRates.parse("""
                KEY,FREQ,CURRENCY,CURRENCY_DENOM,EXR_TYPE,EXR_SUFFIX,TIME_PERIOD,OBS_VALUE
                EXR.D.USD.EUR.SP00.A,D,USD,EUR,SP00,A,2026-11-23,1.0939
                """, "ZAR")).isInstanceOf(EcbRates.EcbResponseException.class).hasMessageContaining("USD/EUR");
    }

    @Test
    void readsTheBankOfNamibiasEuroRate() throws IOException {
        SOURCE.stubFor(get(urlPathEqualTo("/ratestable/api/rates")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(read("bon/rates-2026-11-23.json"))));

        BonRates.NadPerEur rate = bon().euroRate().orElseThrow();

        assertThat(rate.valueDate()).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThat(rate.rate()).isEqualByComparingTo(new BigDecimal("19.1815"));
    }

    @Test
    void anUnavailableOrChangedBankOfNamibiaTableIsNoCrossCheck() {
        SOURCE.stubFor(get(urlPathEqualTo("/ratestable/api/rates")).willReturn(aResponse().withStatus(502)));
        assertThat(bon().euroRate()).isEmpty();

        SOURCE.stubFor(get(urlPathEqualTo("/ratestable/api/rates")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"rateDollar":"17.4623","rateEuroDate":"23 Nov 2026"}""")));
        assertThat(bon().euroRate()).isEmpty();

        SOURCE.stubFor(get(urlPathEqualTo("/ratestable/api/rates")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody("""
                        {"rateEuro":"19.1815","rateEuroDate":"2026/11/23"}""")));
        assertThat(bon().euroRate()).isEmpty();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder csv(String fixture)
            throws IOException {
        return aResponse().withStatus(200).withHeader("Content-Type", "text/csv").withBody(read(fixture));
    }

    private static EcbRates ecb(CircuitBreakerRegistry breakers) {
        ReferenceRateProperties properties = properties();
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + SOURCE.port()).build();
        return new EcbRates(client, breakers, properties, new SimpleMeterRegistry());
    }

    private static BonRates bon() {
        RestClient client = RestClient.builder().baseUrl("http://127.0.0.1:" + SOURCE.port() + "/ratestable/api/rates")
                .build();
        return new BonRates(client, CircuitBreakerRegistry.ofDefaults());
    }

    static ReferenceRateProperties properties() {
        return new ReferenceRateProperties("ecb", URI.create("http://127.0.0.1:1"), null, null, null, null, 2, null,
                null, null, 0, null, null,
                new ReferenceRateProperties.Retry(2, Duration.ofMillis(10), Duration.ofSeconds(5)));
    }
}
