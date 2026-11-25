package com.freightflow.banking.tls;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Mutual TLS on this service's port (ADR 0040): the connector asks every client for a certificate from the internal CA
 * ({@code server.ssl.client-auth: need}), and {@link CallerCertificateFilter} lets through only the identities listed.
 * Off only where there is no TLS connector to check: MockMvc and the tests that start the server without TLS.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TlsConfig.TlsProperties.class)
public class TlsConfig {

    /**
     * @param mutual         whether callers are checked by certificate (true wherever the connector takes TLS)
     * @param allowedCallers the identities (certificate common names) this service takes requests from
     */
    @ConfigurationProperties("freightflow.tls")
    public record TlsProperties(Boolean mutual, List<String> allowedCallers) {
        public TlsProperties {
            mutual = mutual == null || mutual;
            allowedCallers = allowedCallers == null ? List.of() : List.copyOf(allowedCallers);
        }
    }

    @Bean
    InternalTls internalTls(SslBundles bundles) {
        return new InternalTls(bundles);
    }

    @Bean
    @ConditionalOnProperty(name = "freightflow.tls.mutual", havingValue = "true", matchIfMissing = true)
    FilterRegistrationBean<CallerCertificateFilter> callerCertificateFilter(TlsProperties properties,
                                                                           MeterRegistry meters) {
        FilterRegistrationBean<CallerCertificateFilter> registration =
                new FilterRegistrationBean<>(new CallerCertificateFilter(properties.allowedCallers(), meters));
        // Before the issuer-outage filter (-101) and Spring Security's chain (-100): an unexpected caller is refused
        // before its token is checked.
        registration.setOrder(-102);
        return registration;
    }
}
