package com.freightflow.logistics.banking;

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
    ClientHttpRequestFactoryBuilder<?> clientHttpRequestFactoryBuilder() {
        return ClientHttpRequestFactoryBuilder.jdk()
                .withHttpClientCustomizer(builder -> builder.version(HttpClient.Version.HTTP_1_1));
    }
}
