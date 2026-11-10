package com.freightflow.logistics.support;

import com.github.tomakehurst.wiremock.WireMockServer;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * One WireMock server per test JVM standing in for banking-service. Tests register the stubs they need and
 * reset them afterwards; stubs are checked against banking's committed OpenAPI document (ADR 0014).
 */
public final class BankingStub {

    public static final WireMockServer SERVER = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        SERVER.start();
        Runtime.getRuntime().addShutdownHook(new Thread(SERVER::stop));
    }

    private BankingStub() {
    }

    public static String baseUrl() {
        return SERVER.baseUrl();
    }
}
