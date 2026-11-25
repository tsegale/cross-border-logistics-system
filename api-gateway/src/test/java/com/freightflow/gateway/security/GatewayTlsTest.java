package com.freightflow.gateway.security;

import com.freightflow.gateway.support.TestCertificates;
import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The public listener is HTTPS only, with Mozilla's intermediate profile (guidelines 6.0) and HSTS (ADR 0039): what a
 * client that is not on that profile gets.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class GatewayTlsTest {

    static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        UPSTREAM.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("BANKING_SERVICE_URI", () -> TestIssuer.url(UPSTREAM));
        registry.add("LOGISTICS_SERVICE_URI", () -> TestIssuer.url(UPSTREAM));
        registry.add("NOTIFICATION_SERVICE_URI", () -> TestIssuer.url(UPSTREAM));
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop();
    }

    @LocalServerPort
    private int port;

    @Test
    void everyAnswerTellsBrowsersToKeepToHttpsForTwoYears() {
        TestIssuer.gateway(port).responseTimeout(Duration.ofSeconds(10)).build()
                .get().uri("/api/v1/shipments").exchange()
                .expectStatus().isUnauthorized()
                .expectHeader().valueEquals("Strict-Transport-Security", "max-age=63072000 ; includeSubDomains");
    }

    @Test
    void aPlaintextRequestGetsNoHttpAnswer() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write("GET /api/v1/shipments HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            byte[] answer = in.readNBytes(5);
            assertThat(new String(answer, StandardCharsets.US_ASCII)).doesNotStartWith("HTTP");
        }
    }

    @Test
    void aClientOfferingOnlyACipherOutsideTheProfileIsRefused() throws Exception {
        // CBC with SHA-256: TLS 1.2, allowed by the JDK, not in Mozilla's intermediate list.
        try (SSLSocket socket = (SSLSocket) trustingTheTestCa().getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setEnabledCipherSuites(new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256"});
            assertThatThrownBy(socket::startHandshake).isInstanceOf(SSLHandshakeException.class);
        }
    }

    @Test
    void aTls12ClientOnTheProfileIsServed() throws Exception {
        try (SSLSocket socket = (SSLSocket) trustingTheTestCa().getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setEnabledProtocols(new String[] {"TLSv1.2"});
            socket.setEnabledCipherSuites(new String[] {"TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256"});
            socket.startHandshake();
            assertThat(socket.getSession().getProtocol()).isEqualTo("TLSv1.2");
            assertThat(socket.getSession().getCipherSuite()).isEqualTo("TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256");
        }
    }

    private static SSLContext trustingTheTestCa() throws Exception {
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        roots.setCertificateEntry("test-ca", CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(TestCertificates.caPem().getBytes(StandardCharsets.US_ASCII))));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(roots);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        return context;
    }
}
