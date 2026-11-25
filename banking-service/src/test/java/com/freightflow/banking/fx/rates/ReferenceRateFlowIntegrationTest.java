package com.freightflow.banking.fx.rates;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.support.TestCertificates;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.approval.ApprovalKind;
import com.freightflow.banking.approval.ApprovalService;
import com.freightflow.banking.approval.ApprovalStatus;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.fx.FxQuoteService;
import com.freightflow.banking.fx.dto.CreateFxQuoteRequest;
import com.freightflow.banking.fx.dto.FxQuoteResponse;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IsolatedDatabase;
import com.freightflow.banking.support.SharedContainers;
import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.support.TestSecurity;
import com.freightflow.banking.support.TestSpans;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.freightflow.banking.fx.rates.TargetCalendarTest.read;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Live reference rates from end to end (ADR 0034), against the recorded ECB and Bank of Namibia responses and a clock
 * the test moves through a week in Frankfurt. One story in one test, on a database of its own: reference rates are
 * never deleted, so this test owns every row of the table it sees.
 */
@SpringBootTest(properties = {
        // MockMvc, no TLS connector: no client certificate to check (ADR 0040).
        "server.ssl.enabled=false",
        "freightflow.tls.mutual=false",
        "freightflow.banking.fx.reference.provider=ecb",
        // The test calls the refresher itself; the schedule never fires during it.
        "freightflow.banking.fx.reference.initial-delay=1h",
        "freightflow.banking.fx.reference.refresh-interval=1h",
        "freightflow.banking.fx.reference.retry.delay=10ms",
        "freightflow.banking.approvals.ttl=30d",
        "freightflow.banking.holds.default-ttl=30d"
})
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
@Import({TestSecurity.class, Funding.class, TestSpans.class, ReferenceRateFlowIntegrationTest.Frankfurt.class})
@Testcontainers(disabledWithoutDocker = true)
class ReferenceRateFlowIntegrationTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final WireMockServer SOURCES = new WireMockServer(wireMockConfig().dynamicPort());
    private static final IsolatedDatabase DATABASE = IsolatedDatabase.create("reference_rates");
    private static final AtomicReference<Instant> NOW = new AtomicReference<>(frankfurt("2026-11-23T18:00"));

    @DynamicPropertySource
    static void isolatedDatabaseAndRecordedSources(DynamicPropertyRegistry registry) {
        if (!SOURCES.isRunning()) {
            SOURCES.start();
        }
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", DATABASE::username);
        registry.add("spring.datasource.password", DATABASE::password);
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::bootstrapServers);
        // Every internal TLS peer (the broker, KafkaBroker) is verified against this JVM's test CA.
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("banking-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("banking-service").privateKeyPem());
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
        registry.add("KAFKA_USERNAME", () -> "banking");
        registry.add("freightflow.kafka.password", () -> SharedContainers.KAFKA.password("banking"));
        registry.add("freightflow.banking.fx.reference.ecb-base-url", () -> "http://127.0.0.1:" + SOURCES.port());
        registry.add("freightflow.banking.fx.reference.bon-url",
                () -> "http://127.0.0.1:" + SOURCES.port() + "/ratestable/api/rates");
    }

    @AfterAll
    static void stop() {
        SOURCES.stop();
    }

    @TestConfiguration
    static class Frankfurt {
        @Bean
        @Primary
        Clock movableClock() {
            return new Clock() {
                @Override
                public Instant instant() {
                    return NOW.get();
                }

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    throw new UnsupportedOperationException("UTC only");
                }
            };
        }
    }

    @Autowired
    private ReferenceRateRefresher refresher;

    @Autowired
    private FxQuoteService quotes;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aWeekOfReferenceRates() throws Exception {
        // Monday 23 November 2026, 18:00 in Frankfurt: the first fetch. Only the newest observation is taken, and the
        // Bank of Namibia's 19.1815 NAD per EUR for the same day backs it (NAD/ZAR 1.0016, inside the 2% tolerance).
        serveEcb(read("ecb/zar-latest-5.csv"));
        SOURCES.stubFor(get(urlPathEqualTo("/ratestable/api/rates")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(read("bon/rates-2026-11-23.json"))));

        List<ReferenceRate> first = refresher.refresh();
        assertThat(first).singleElement().satisfies(rate -> {
            assertThat(rate.getValueDate()).isEqualTo(LocalDate.parse("2026-11-23"));
            assertThat(rate.getStatus()).isEqualTo(ReferenceRateStatus.CONFIRMED);
        });
        UUID monday = first.getFirst().getId();
        assertThat(refresher.refresh()).as("a second fetch finds nothing new").isEmpty();

        // A quote and a hold record the rate that priced them. EUR to NAD is the ECB's ZAR rate under the CMA peg.
        FxQuoteResponse quote = quotes.createQuote(TestCallers.CUSTOMER_NAMIB,
                new CreateFxQuoteRequest("EUR", "NAD", new BigDecimal("1000.00"), null));
        assertThat(quote.midRate()).isEqualByComparingTo("19.15130000");
        assertThat(quote.buyAmount()).isEqualByComparingTo("19055.54");
        assertThat(quote.rateSource()).isEqualTo("ecb+cma-peg");
        assertThat(quote.rateValueDate()).isEqualTo(LocalDate.parse("2026-11-23"));
        assertThat(jdbcTemplate.queryForObject("SELECT reference_rate_id FROM fx_quotes WHERE id = ?", UUID.class,
                quote.id())).isEqualTo(monday);

        AccountResponse payer = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("NAD", TestCallers.NAMIB));
        funding.deposit(payer.id().toString(), new BigDecimal("50000.00"), "NAD", "SEED");
        mockMvc.perform(post("/api/v1/holds").with(TestCallers.logistics()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"payerOrganizationId":"%s","payerAccount":"%s","amount":500.00,"currency":"EUR","reference":"SHIPMENT FF-RATES"}
                                """.formatted(TestCallers.NAMIB, payer.accountNumber())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rateSource").value("ecb+cma-peg"))
                .andExpect(jsonPath("$.rateValueDate").value("2026-11-23"));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reference_rate_id FROM holds WHERE reference = 'SHIPMENT FF-RATES'", UUID.class))
                .isEqualTo(monday);

        // Tuesday 18:00: the ECB's rate is 7% up on Monday's. It is stored, quarantined, and not used.
        NOW.set(frankfurt("2026-11-24T18:00"));
        serveEcb(read("ecb/zar-latest-5.csv") + "EXR.D.ZAR.EUR.SP00.A,D,ZAR,EUR,SP00,A,2026-11-24,20.4919\n");
        ReferenceRate tuesday = refresher.refresh().getFirst();
        assertThat(tuesday.getStatus()).isEqualTo(ReferenceRateStatus.QUARANTINED);
        assertThat(tuesday.getQuarantineReason()).contains("moved 7.00%");
        assertThat(quotes.createQuote(TestCallers.CUSTOMER_NAMIB,
                new CreateFxQuoteRequest("EUR", "ZAR", new BigDecimal("100.00"), null)).midRate())
                .isEqualByComparingTo("19.15130000");
        assertThat(meters.get("freightflow.fx.reference_rates.quarantined").gauge().value()).isEqualTo(1.0);

        // One operator asks for it to be confirmed; the same operator cannot approve it; a second one does.
        ApprovalResponse requested = approvals.request(TestCallers.OPERATOR, new CreateApprovalRequest(
                ApprovalKind.RATE_CONFIRMATION, null, null, null, null, null,
                "Checked against Reuters 24 Nov: ZAR fell on the rating news", tuesday.getId()), key());
        assertThat(requested.referenceRateId()).isEqualTo(tuesday.getId());
        assertThatThrownBy(() -> approvals.approve(TestCallers.OPERATOR, requested.id(), null, key()))
                .hasMessageContaining("other than the one who made it");
        ApprovalResponse approved = approvals.approve(TestCallers.SECOND_OPERATOR, requested.id(), "Agreed", key());
        assertThat(approved.status()).isEqualTo(ApprovalStatus.APPROVED);
        assertThat(approved.postedEntryId()).as("a confirmation posts nothing").isNull();
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM reference_rates WHERE id = ?", String.class,
                tuesday.getId())).isEqualTo("CONFIRMED");
        assertThat(quotes.createQuote(TestCallers.CUSTOMER_NAMIB,
                new CreateFxQuoteRequest("EUR", "ZAR", new BigDecimal("100.00"), null)).midRate())
                .isEqualByComparingTo("20.49190000");

        // The ECB stops answering. By Friday 18:00 the newest rate, Tuesday's, is three working days behind the one
        // due: FX is refused with a problem, not priced on it.
        SOURCES.resetMappings();
        SOURCES.stubFor(get(urlPathEqualTo("/service/data/EXR/D.ZAR.EUR.SP00.A")).willReturn(aResponse().withStatus(503)));
        NOW.set(frankfurt("2026-11-27T18:00"));
        refresher.refreshIfDue();
        assertThat(meters.get("freightflow.fx.reference_rate.age").gauge().value()).isEqualTo(3.0);
        mockMvc.perform(post("/api/v1/fx-quotes").with(TestCallers.as(TestCallers.CUSTOMER_NAMIB))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sellCurrency\":\"NAD\",\"buyCurrency\":\"EUR\",\"sellAmount\":100.00}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "900"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/fx-rates-stale"))
                .andExpect(jsonPath("$.rateValueDate").value("2026-11-24"))
                .andExpect(jsonPath("$.expectedValueDate").value("2026-11-27"))
                .andExpect(jsonPath("$.maxAgeWorkingDays").value(2));
        // Indicative rates leave the stale pair out and say so, rather than failing: logistics quotes without a total.
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/fx-rates").param("base", "NAD").with(TestCallers.as(TestCallers.CUSTOMER_NAMIB)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates.length()").value(1))
                .andExpect(jsonPath("$.rates[0].currency").value("ZAR"))
                .andExpect(jsonPath("$.rates[0].source").value("cma-peg"))
                .andExpect(jsonPath("$.unavailable[0]").value("EUR"));
        // The peg needs no rate: NAD to ZAR still quotes.
        assertThat(quotes.createQuote(TestCallers.CUSTOMER_NAMIB,
                new CreateFxQuoteRequest("NAD", "ZAR", new BigDecimal("100.00"), null)).rateSource())
                .isEqualTo("cma-peg");

        // Stored rates are never changed or deleted, whatever the code path.
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE reference_rates SET rate = 1 WHERE id = ?", monday))
                .hasMessageContaining("never overwritten");
        assertThatThrownBy(() -> jdbcTemplate.update("DELETE FROM reference_rates WHERE id = ?", monday))
                .hasMessageContaining("never deleted");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE reference_rates SET status = 'REJECTED', decided_at = now(), decided_approval_id = ? WHERE id = ?",
                requested.id(), tuesday.getId()))
                .hasMessageContaining("only a quarantined rate is decided");
        assertThat(meters.get("freightflow.fx.reference_rates.refreshes").tag("outcome", "failed").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    private static void serveEcb(String csv) {
        SOURCES.stubFor(get(urlPathEqualTo("/service/data/EXR/D.ZAR.EUR.SP00.A")).willReturn(aResponse()
                .withStatus(200).withHeader("Content-Type", "text/csv").withBody(csv)));
    }

    private static Instant frankfurt(String local) {
        return LocalDateTime.parse(local).atZone(BERLIN).toInstant();
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
