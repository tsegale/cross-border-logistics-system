package com.freightflow.gateway.security;

import com.freightflow.gateway.support.TestCertificates;
import com.freightflow.gateway.support.TestIssuer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.concurrent.Executors;

/**
 * The gateway calls the services over mutual TLS (ADR 0040): an upstream that demands a client certificate from the
 * test CA, as banking's connector does, gets the gateway's, and says whose it got.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class GatewayMutualTlsTest {

    static final HttpsServer BANKING = banking();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("BANKING_SERVICE_URI", () -> "https://127.0.0.1:" + BANKING.getAddress().getPort());
    }

    @AfterAll
    static void stop() {
        BANKING.stop(0);
    }

    @LocalServerPort
    private int port;

    @Test
    void theGatewayPresentsItsCertificateToAServiceThatAsksForOne() {
        TestIssuer.gateway(port).responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/v1/accounts/NA-0000-0000-01")
                .header(HttpHeaders.AUTHORIZATION, TestIssuer.bearer(TestIssuer.role("operator")))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).isEqualTo("CN=api-gateway");
    }

    /** banking's connector in small: its certificate for 127.0.0.1, and a client certificate from the test CA required. */
    private static HttpsServer banking() {
        try {
            SSLContext tls = TestCertificates.clientContext(TestCertificates.of("banking-service"));
            HttpsServer server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setHttpsConfigurator(new HttpsConfigurator(tls) {
                @Override
                public void configure(HttpsParameters parameters) {
                    parameters.setNeedClientAuth(true);
                }
            });
            server.createContext("/", exchange -> {
                String caller;
                try {
                    caller = ((X509Certificate) ((HttpsExchange) exchange).getSSLSession().getPeerCertificates()[0])
                            .getSubjectX500Principal().getName();
                } catch (SSLPeerUnverifiedException none) {
                    caller = "none";
                }
                byte[] body = caller.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/plain");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.setExecutor(Executors.newSingleThreadExecutor());
            server.start();
            return server;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
