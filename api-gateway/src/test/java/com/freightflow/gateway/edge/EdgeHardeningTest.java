package com.freightflow.gateway.edge;

import com.freightflow.gateway.support.TestCertificates;
import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import javax.net.ssl.SSLSocket;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/** The edge's response headers, its closed CORS, and what a slow or bloated request is allowed (ADR 0042). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "management.server.port=0",
        "server.netty.idle-timeout=2s",
        "freightflow.gateway.read-timeout=1s",
        "freightflow.gateway.request-timeout=3s"
})
class EdgeHardeningTest {

    static final WireMockServer UPSTREAM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        UPSTREAM.start();
        UPSTREAM.stubFor(any(anyUrl()).willReturn(okJson("{}")));
        UPSTREAM.stubFor(any(urlPathMatching("/realms/.*")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Security-Policy", "frame-ancestors 'self'").withBody("login")));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("LOGISTICS_SERVICE_URI", () -> TestIssuer.url(UPSTREAM));
        registry.add("BANKING_SERVICE_URI", () -> TestIssuer.url(UPSTREAM));
        registry.add("KEYCLOAK_SERVICE_URI", () -> TestIssuer.url(UPSTREAM));
    }

    @AfterAll
    static void stop() {
        UPSTREAM.stop();
    }

    @LocalServerPort
    private int port;

    private WebTestClient client() {
        return TestIssuer.gateway(port).responseTimeout(Duration.ofSeconds(10)).build();
    }

    @Test
    void anApiResponseCarriesTheSecurityHeaders() {
        client().get().uri("/api/v1/shipments").header(HttpHeaders.AUTHORIZATION, TestIssuer.bearer(TestIssuer.customer()))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
                .expectHeader().valueEquals("Referrer-Policy", "no-referrer")
                .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
                .expectHeader().valueEquals("X-Frame-Options", "DENY")
                .expectHeader().valueEquals("Cross-Origin-Resource-Policy", "same-origin")
                .expectHeader().valueEquals("Cache-Control", "no-store")
                .expectHeader().exists("Strict-Transport-Security");
    }

    /** ZAP found HSTS missing here: a filter that answers before the security chain still gets every header. */
    @Test
    void anInternalPathsNotFoundCarriesTheSecurityHeadersToo() {
        client().post().uri("/api/v1/holds").header(HttpHeaders.AUTHORIZATION, TestIssuer.bearer(TestIssuer.customer()))
                .exchange()
                .expectStatus().isNotFound()
                .expectHeader().exists("Strict-Transport-Security")
                .expectHeader().valueEquals("Cross-Origin-Resource-Policy", "same-origin")
                .expectHeader().valueEquals("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
    }

    @Test
    void keycloaksPagesKeepTheirOwnPolicy() {
        client().get().uri("/realms/freightflow/protocol/openid-connect/auth").exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Content-Security-Policy", "frame-ancestors 'self'");
    }

    @Test
    void noOriginIsAllowedAcrossSites() {
        client().method(HttpMethod.OPTIONS).uri("/api/v1/shipments")
                .header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization")
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS);
        client().get().uri("/api/v1/shipments").header(HttpHeaders.ORIGIN, "https://attacker.example")
                .header(HttpHeaders.AUTHORIZATION, TestIssuer.bearer(TestIssuer.customer()))
                .exchange()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void headersOver16KbAreRefused() {
        client().get().uri("/api/v1/shipments").header(HttpHeaders.AUTHORIZATION, TestIssuer.bearer(TestIssuer.customer()))
                .header("X-Padding", "a".repeat(17 * 1024))
                .exchange()
                .expectStatus().isEqualTo(431);
    }

    @Test
    void aClientThatStopsSendingItsHeadersIsDisconnected() throws IOException {
        // Half a request line, then silence: the idle timeout (2 s here) closes the connection.
        assertClosedAfterSilence("GET /api/v1/shipm");
    }

    @Test
    void aClientThatStopsSendingItsBodyIsDisconnected() throws IOException {
        // The headers, then 10 of the 100 bytes they announce: the read timeout (1 s here) closes the connection.
        assertClosedAfterSilence("POST /api/v1/quotes HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: "
                + TestIssuer.bearer(TestIssuer.customer()) + "\r\nContent-Type: application/json\r\n"
                + "Content-Length: 100\r\n\r\n{\"origin\":");
    }

    private void assertClosedAfterSilence(String sent) throws IOException {
        try (SSLSocket socket = (SSLSocket) TestCertificates.clientContext(null).getSocketFactory()
                .createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            out.write(sent.getBytes(StandardCharsets.US_ASCII));
            out.flush();
            long started = System.nanoTime();
            InputStream in = socket.getInputStream();
            try {
                while (in.read() != -1) {
                    // An error response, if any, before the close.
                }
            } catch (SocketTimeoutException e) {
                throw new AssertionError("the gateway kept a silent connection open for 10 s", e);
            } catch (IOException closed) {
                // A reset is a close too.
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(8));
        }
    }
}
