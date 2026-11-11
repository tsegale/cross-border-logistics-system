package com.freightflow.logistics.support;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.Network;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;

/**
 * A real Keycloak 26.8.2 with the repository's realm file imported (ADR 0016), through the Testcontainers module.
 * The realm's placeholders get test values. Its public URL is {@code http://keycloak:8080}, so tokens fetched from the
 * host carry the same {@code iss} that a container on the network validates.
 */
public final class RealmKeycloak {

    public static final String IMAGE = "quay.io/keycloak/keycloak:26.8.2";
    public static final String ISSUER = "http://keycloak:8080/realms/freightflow";
    public static final String LOGISTICS_SECRET = "test-logistics-secret";
    public static final String CARRIER_SECRET = "test-carrier-secret";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private RealmKeycloak() {
    }

    @SuppressWarnings("resource") // started by the test class that owns it, stopped by Testcontainers' reaper
    public static KeycloakContainer container(Network network) {
        KeycloakContainer keycloak = new KeycloakContainer(IMAGE)
                .withRealmImportFile("keycloak/freightflow-realm.json")
                // First start imports the realm; on a loaded laptop that took 180 s (ADR 0016).
                .withStartupTimeout(java.time.Duration.ofMinutes(8))
                .withEnv(Map.of(
                        "KC_HOSTNAME", "http://keycloak:8080",
                        "KC_HOSTNAME_BACKCHANNEL_DYNAMIC", "true",
                        "KEYCLOAK_WEB_ORIGIN", "http://127.0.0.1:3000",
                        "KEYCLOAK_LOGISTICS_SERVICE_SECRET", LOGISTICS_SECRET,
                        "KEYCLOAK_CARRIER_WALVIS_HAULAGE_SECRET", CARRIER_SECRET,
                        "KEYCLOAK_PASSWORD_CUSTOMER_NAMIB_EXPORTS", "test-password-namib",
                        "KEYCLOAK_PASSWORD_CUSTOMER_HANSEATIC_IMPORTS", "test-password-hanseatic",
                        "KEYCLOAK_PASSWORD_OPERATOR", "test-password-operator",
                        "KEYCLOAK_PASSWORD_AUDITOR", "test-password-auditor"));
        if (network != null) {
            keycloak.withNetwork(network).withNetworkAliases("keycloak");
        }
        return keycloak;
    }

    public static String tokenUri(KeycloakContainer keycloak) {
        return keycloak.getAuthServerUrl() + "/realms/freightflow/protocol/openid-connect/token";
    }

    /** A client-credentials token from the realm, as logistics or a carrier gets one. */
    public static String clientToken(KeycloakContainer keycloak, String clientId, String secret) {
        LinkedMultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", secret);
        String body = RestClient.create().post().uri(tokenUri(keycloak)).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(String.class);
        return JSON.readTree(body).get("access_token").asString();
    }

    /**
     * A test-only confidential client whose service account is an operator, so a test can set banking up through its
     * API. Not in the realm file: production has no such client.
     */
    public static void addOperatorTestClient(KeycloakContainer keycloak, String clientId, String secret) {
        RealmResource realm = keycloak.getKeycloakAdminClient().realm("freightflow");
        ClientRepresentation client = new ClientRepresentation();
        client.setClientId(clientId);
        client.setPublicClient(false);
        client.setSecret(secret);
        client.setServiceAccountsEnabled(true);
        client.setStandardFlowEnabled(false);
        client.setFullScopeAllowed(false);
        client.setDefaultClientScopes(List.of("freightflow-api"));
        realm.clients().create(client).close();
        String id = realm.clients().findByClientId(clientId).getFirst().getId();
        RoleRepresentation operator = realm.roles().get("operator").toRepresentation();
        String serviceAccount = realm.clients().get(id).getServiceAccountUser().getId();
        realm.users().get(serviceAccount).roles().realmLevel().add(List.of(operator));
    }

    public static JsonNode claims(String token) {
        String payload = token.split("\\.")[1];
        return JSON.readTree(java.util.Base64.getUrlDecoder().decode(payload));
    }
}
