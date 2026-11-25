package com.freightflow.banking.fx.rates;

import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.telemetry.BoundedGauge;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;

/**
 * Live reference rates (ADR 0034). The stored rates, their checks and their gauges always exist, so a quarantined rate
 * can be decided whichever provider prices; the ECB provider, its clients and the refresher only with
 * {@code freightflow.banking.fx.reference.provider=ecb}, the default.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ReferenceRateProperties.class)
public class ReferenceRateConfig {

    @Bean
    ReferenceRateService referenceRateService(ReferenceRateRepository rates, JdbcTemplate jdbcTemplate,
                                              ReferenceRateProperties properties, MeterRegistry meters, Clock clock) {
        return new ReferenceRateService(rates, jdbcTemplate, properties, meters, clock);
    }

    /** Pricing from the ECB, the default; {@code provider=static} prices from the fixed table instead. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "freightflow.banking.fx.reference.provider", havingValue = "ecb",
            matchIfMissing = true)
    static class Ecb {

        @Bean
        ReferenceExchangeRateProvider referenceExchangeRateProvider(ReferenceRateService rates,
                                                                    BankingProperties properties) {
            return new ReferenceExchangeRateProvider(rates, properties);
        }

        @Bean
        EcbRates ecbRates(RestClient.Builder builder, CircuitBreakerRegistry breakers, ReferenceRateProperties properties,
                          MeterRegistry meters, SslBundles bundles,
                          @Value("${freightflow.banking.fx.reference.ssl-bundle:}") String sslBundle) {
            RestClient client = builder.clone()
                    .baseUrl(properties.ecbBaseUrl().toString())
                    .requestFactory(requestFactory(properties, bundles, sslBundle))
                    .defaultHeader("Accept", "text/csv")
                    .build();
            return new EcbRates(client, breakers, properties, meters);
        }

        /** No Bank of Namibia URL, no cross-check: the first rate then always waits for two operators. */
        @Bean
        BonRates bonRates(RestClient.Builder builder, CircuitBreakerRegistry breakers, ReferenceRateProperties properties,
                          SslBundles bundles, @Value("${freightflow.banking.fx.reference.ssl-bundle:}") String sslBundle) {
            if (properties.bonUrl() == null) {
                return null;
            }
            RestClient client = builder.clone()
                    .baseUrl(properties.bonUrl().toString())
                    .requestFactory(requestFactory(properties, bundles, sslBundle))
                    .defaultHeader("Accept", MediaType.APPLICATION_JSON_VALUE)
                    .build();
            return new BonRates(client, breakers);
        }

        @Bean
        ReferenceRateRefresher referenceRateRefresher(ReferenceRateService service, EcbRates ecb,
                                                      ObjectProvider<BonRates> bon,
                                                      ReferenceRateProperties properties, MeterRegistry meters) {
            return new ReferenceRateRefresher(service, ecb, bon.getIfAvailable(), properties, meters);
        }
    }

    /**
     * Gauges read from the table (ADR 0031), the same on every instance: how many working days the pricing rate is
     * behind the one due (+Inf with none), the limit it is held to, and how many rates wait in quarantine.
     */
    @Bean
    ReferenceRateMetrics referenceRateMetrics(ReferenceRateService service, JdbcTemplate jdbcTemplate,
                                              ReferenceRateProperties properties, MeterRegistry meters) {
        ReferenceRateMetrics metrics = new ReferenceRateMetrics(service, jdbcTemplate);
        Gauge.builder("freightflow.fx.reference_rate.age", new BoundedGauge(metrics::age), BoundedGauge::value)
                .strongReference(true)
                .tag("pair", ReferenceRateService.BASE + "/" + ReferenceRateService.ZAR)
                .description("Working days the confirmed reference rate that prices is behind the one due")
                .register(meters);
        Gauge.builder("freightflow.fx.reference_rate.max_age", properties, p -> p.maxAgeWorkingDays())
                .description("Working days a reference rate may be behind the one due before FX is refused")
                .register(meters);
        Gauge.builder("freightflow.fx.reference_rates.quarantined", new BoundedGauge(metrics::quarantined),
                        BoundedGauge::value)
                .strongReference(true)
                .description("Reference rates waiting for two operators to confirm or reject them")
                .register(meters);
        return metrics;
    }

    /**
     * HTTP/1.1, as for logistics' banking client: no h2c upgrade offer on plain http (ADR 0013). The ECB and the Bank
     * of Namibia have certificates from public CAs, checked against the JVM's roots; {@code ssl-bundle} names a bundle
     * to check against instead, {@code internal} where the partner simulator stands in for them (ADR 0040), so the
     * internal CA is never trusted for a public host.
     */
    private static ClientHttpRequestFactory requestFactory(ReferenceRateProperties properties, SslBundles bundles,
                                                           String sslBundle) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(properties.connectTimeout(), properties.readTimeout());
        if (sslBundle != null && !sslBundle.isBlank()) {
            settings = settings.withSslBundle(bundles.getBundle(sslBundle));
        }
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(client -> client.version(HttpClient.Version.HTTP_1_1))
                .build(settings);
    }

    /** The queries behind the gauges. */
    public static final class ReferenceRateMetrics {
        private final ReferenceRateService service;
        private final JdbcTemplate jdbcTemplate;

        ReferenceRateMetrics(ReferenceRateService service, JdbcTemplate jdbcTemplate) {
            this.service = service;
            this.jdbcTemplate = jdbcTemplate;
        }

        double age() {
            return service.ageInWorkingDays();
        }

        double quarantined() {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM reference_rates WHERE status = 'QUARANTINED'", Long.class);
            return count == null ? 0 : count;
        }
    }
}
