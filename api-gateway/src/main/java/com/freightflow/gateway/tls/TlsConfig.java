package com.freightflow.gateway.tls;

import org.springframework.boot.ssl.SslBundles;
import org.springframework.cloud.gateway.config.HttpClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Mutual TLS from the gateway to the services (ADR 0040): every route's connection presents the gateway's certificate
 * (the {@code internal} bundle, followed through each renewal) and checks the service's against the internal root by
 * name. The warm-up's requests to the gateway's own listener go through the same client.
 */
@Configuration(proxyBeanMethods = false)
public class TlsConfig {

    @Bean
    InternalTls internalTls(SslBundles bundles) {
        return new InternalTls(bundles);
    }

    @Bean
    HttpClientCustomizer mutualTlsToTheServices(InternalTls tls) {
        return http -> http.secure(spec -> spec.sslContext(tls.nettyClient()));
    }
}
