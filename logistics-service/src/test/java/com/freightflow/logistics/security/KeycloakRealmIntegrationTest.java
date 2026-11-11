package com.freightflow.logistics.security;

import com.freightflow.logistics.support.RealmKeycloak;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.idm.OrganizationRepresentation;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The realm file the stack imports (ADR 0016), imported into a real Keycloak 26.8.2: organisations with their fixed
 * ids and members, the machine clients' tokens with exactly the audiences, scope and roles the services check, RS256
 * and a 5-minute lifetime, and no password grant anywhere. People's logins are exercised by scripts/token.sh and the
 * smoke test against the compose stack.
 */
@Testcontainers(disabledWithoutDocker = true)
class KeycloakRealmIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static KeycloakContainer keycloak;

    @BeforeAll
    static void start() {
        keycloak = RealmKeycloak.container(null);
        keycloak.start();
    }

    @AfterAll
    static void stop() {
        keycloak.stop();
    }

    @Test
    void organisationsKeepTheirIdsAndMembers() {
        List<OrganizationRepresentation> organizations =
                keycloak.getKeycloakAdminClient().realm("freightflow").organizations().getAll();
        assertThat(organizations).extracting(OrganizationRepresentation::getAlias)
                .containsExactlyInAnyOrder("namib-exports", "hanseatic-imports");
        OrganizationRepresentation namib = organizations.stream().filter(o -> o.getAlias().equals("namib-exports"))
                .findFirst().orElseThrow();
        assertThat(namib.getId()).isEqualTo("0193a0e0-0000-7000-8000-00000000a001");
        assertThat(keycloak.getKeycloakAdminClient().realm("freightflow").organizations().get(namib.getId())
                .members().list(0, 10)).extracting(member -> member.getUsername())
                .containsExactly("customer-namib-exports");
    }

    @Test
    void logisticsGetsATokenForBankingOnlyWithItsHoldsScope() {
        String token = RealmKeycloak.clientToken(keycloak, "logistics-service", RealmKeycloak.LOGISTICS_SECRET);
        JsonNode claims = RealmKeycloak.claims(token);
        assertThat(audiences(claims)).containsExactly("banking-service");
        assertThat(claims.get("scope").asString()).isEqualTo("banking.holds");
        assertThat(claims.get("azp").asString()).isEqualTo("logistics-service");
        assertThat(claims.has("roles")).isFalse();
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(300);
        assertThat(header(token).get("alg").asString()).isEqualTo("RS256");
        assertThat(claims.get("iss").asString()).isEqualTo(RealmKeycloak.ISSUER);
    }

    @Test
    void aCarrierTokenNamesTheGatewayAndLogisticsButNeverBanking() {
        String token = RealmKeycloak.clientToken(keycloak, "carrier-walvis-haulage", RealmKeycloak.CARRIER_SECRET);
        JsonNode claims = RealmKeycloak.claims(token);
        assertThat(audiences(claims)).containsExactlyInAnyOrder("api-gateway", "logistics-service");
        assertThat(claims.get("roles")).hasSize(1);
        assertThat(claims.get("roles").get(0).asString()).isEqualTo("carrier");
    }

    @Test
    void thereIsNoPasswordGrant() {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "password");
        form.add("client_id", "freightflow-web");
        form.add("username", "operator");
        form.add("password", "test-password-operator");
        assertThatThrownBy(() -> RestClient.create().post().uri(RealmKeycloak.tokenUri(keycloak))
                .contentType(MediaType.APPLICATION_FORM_URLENCODED).body(form).retrieve().body(String.class))
                .isInstanceOf(HttpClientErrorException.class)
                .hasMessageContaining("unauthorized_client");
    }

    @Test
    void aWrongClientSecretIsRefused() {
        assertThatThrownBy(() -> RealmKeycloak.clientToken(keycloak, "logistics-service", "wrong"))
                .isInstanceOf(HttpClientErrorException.Unauthorized.class);
    }

    private static List<String> audiences(JsonNode claims) {
        JsonNode aud = claims.get("aud");
        if (aud.isArray()) {
            List<String> values = new java.util.ArrayList<>();
            aud.forEach(value -> values.add(value.asString()));
            return values;
        }
        return List.of(aud.asString());
    }

    private static JsonNode header(String token) {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[0]));
    }
}
