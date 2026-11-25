package com.freightflow.gateway.security;

import com.freightflow.gateway.support.TestIssuer;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keycloak publishes no port; the gateway is its public face (ADR 0041). It forwards Keycloak's documented exposed
 * paths without a token, since logging in is how a caller gets one, and nothing of the Admin Console, the Admin REST API
 * or the master realm, which Keycloak serves on the internal network only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
class KeycloakRoutesTest {

    static final WireMockServer KEYCLOAK = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        KEYCLOAK.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        TestIssuer.register(registry);
        registry.add("KEYCLOAK_SERVICE_URI", () -> TestIssuer.url(KEYCLOAK));
    }

    @AfterAll
    static void stop() {
        KEYCLOAK.stop();
    }

    @LocalServerPort
    private int port;

    @BeforeEach
    void reset() {
        KEYCLOAK.resetAll();
        KEYCLOAK.stubFor(any(anyUrl()).willReturn(ok("keycloak")));
    }

    private WebTestClient client() {
        return TestIssuer.gateway(port).responseTimeout(Duration.ofSeconds(10)).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/realms/freightflow/.well-known/openid-configuration",
            "/realms/freightflow/protocol/openid-connect/auth", "/resources/abc/login/keycloak.v2/css/login.css"})
    void theRealmsPublicPathsReachKeycloakWithoutAToken(String path) {
        client().get().uri(path).exchange().expectStatus().isOk().expectBody(String.class).isEqualTo("keycloak");
        KEYCLOAK.verify(1, anyRequestedFor(urlPathEqualTo(path)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/admin/master/console/", "/admin/realms/freightflow/users", "/realms/master",
            "/realms/master/protocol/openid-connect/token", "/realms/master/account"})
    void theAdminConsoleTheAdminApiAndTheMasterRealmAreNotReachable(String path) {
        // /admin/ has no route, so without a token it is a 401 like any unknown path, with one a 404; the master
        // realm is refused by its own route. Either way nothing reaches Keycloak.
        client().get().uri(path).exchange().expectStatus().value(status -> assertThat(status).isIn(401, 404));
        client().post().uri(path).exchange().expectStatus().value(status -> assertThat(status).isIn(401, 403, 404));
        client().get().uri(path).header("Authorization", TestIssuer.bearer(TestIssuer.role("operator"))).exchange()
                .expectStatus().isNotFound();
        assertThat(KEYCLOAK.getAllServeEvents()).as("nothing reached Keycloak").isEmpty();
    }
}
