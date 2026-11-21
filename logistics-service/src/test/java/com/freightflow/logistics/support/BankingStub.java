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

    /** 127.0.0.1, not WireMock's baseUrl(): "localhost" tries IPv6 first on Windows and can reach another listener. */
    public static String baseUrl() {
        return "http://127.0.0.1:" + SERVER.port();
    }
}
