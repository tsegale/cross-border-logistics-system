package com.freightflow.notification.tls;

import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.TestCertificates;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The service's one port takes mutual TLS and only the callers it expects (ADR 0040), checked before the bearer token
 * and in addition to it: what an expected caller, an unexpected one, a client without a certificate and one from
 * another CA each get. The server runs with its real connector, its certificate from the test CA.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // One more cached context beside the others: a small pool, the database's connections are shared.
        "spring.datasource.hikari.maximum-pool-size=2", "spring.datasource.hikari.minimum-idle=1"})
class CallerCertificateIntegrationTest extends IntegrationTest {

    static final String PATH = "/api/v1/notifications";
    static final String UNEXPECTED = "logistics-service";

    @DynamicPropertySource
    static void mutualTls(DynamicPropertyRegistry registry) {
        registry.add("server.ssl.enabled", () -> "true");
        registry.add("freightflow.tls.mutual", () -> "true");
    }

    @LocalServerPort
    private int port;

    @Test
    void anExpectedCallerGetsPastTlsToTheTokenCheck() throws Exception {
        HttpResponse<String> response = get(TestCertificates.clientContext(TestCertificates.of("api-gateway")));
        assertThat(response.statusCode()).as("the token is checked next, and there is none").isEqualTo(401);
    }

    @Test
    void anUnexpectedCallerIsRefusedBeforeItsTokenIsLookedAt() throws Exception {
        HttpResponse<String> response = get(TestCertificates.clientContext(TestCertificates.of(UNEXPECTED)));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.headers().firstValue("Content-Type")).hasValue("application/problem+json");
        assertThat(response.body()).contains("https://freightflow.dev/problems/caller-not-allowed")
                .doesNotContain(UNEXPECTED);
    }

    @Test
    void aClientWithoutACertificateIsRefusedInTheHandshake() {
        assertThatThrownBy(() -> get(TestCertificates.clientContext(null))).isInstanceOf(IOException.class);
    }

    @Test
    void aCertificateFromAnotherCaIsRefusedInTheHandshake() {
        assertThatThrownBy(() -> get(TestCertificates.clientContext(TestCertificates.untrusted("api-gateway"))))
                .isInstanceOf(IOException.class);
    }

    @Test
    void plaintextNeverReachesTheService() throws Exception {
        // Tomcat's TLS connector answers a plaintext request itself, before any filter or controller sees it.
        HttpClient plain = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = plain.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + PATH))
                .timeout(Duration.ofSeconds(5)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("requires TLS").doesNotContain("problems");
    }

    private HttpResponse<String> get(SSLContext tls) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().sslContext(tls).version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5)).build();
        return client.send(HttpRequest.newBuilder(URI.create("https://127.0.0.1:" + port + PATH))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
