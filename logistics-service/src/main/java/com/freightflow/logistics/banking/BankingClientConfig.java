package com.freightflow.logistics.banking;

import com.freightflow.logistics.tls.InternalTls;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.service.registry.ImportHttpServices;

import java.net.http.HttpClient;

/** Base URL and timeouts come from {@code spring.http.serviceclient.banking.*}. */
@Configuration(proxyBeanMethods = false)
@ImportHttpServices(group = "banking", types = BankingApi.class)
public class BankingClientConfig {

    /**
     * HTTP/1.1 only. The JDK client otherwise offers an h2c upgrade on plain http; banking's Tomcat ignores it, but a
     * peer that accepts it (WireMock's Jetty in the tests) can reset the stream mid-request, which surfaces as an
     * unknown payment outcome. Boot still applies the connect and read timeouts to this builder.
     */
    @Bean
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder(InternalTls tls) {
        // Mutual TLS to banking (ADR 0040): logistics' own certificate, followed through each renewal, and banking's
        // checked against the internal root by name. Only the banking group is built from this builder; the DCSA
        // poller, the token and the key clients build their own, so a public endpoint is never held to this root.
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(builder -> builder.version(HttpClient.Version.HTTP_1_1)
                        .sslContext(tls.sslContext()).sslParameters(tls.sslParameters()));
    }
}
