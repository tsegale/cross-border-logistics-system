package com.freightflow.logistics.banking;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * logistics-service's cached banking tokens (ADR 0019). In memory, like Spring Security's
 * {@code InMemoryOAuth2AuthorizedClientService}, plus {@link #evictAll()}: after the client secret rotates, the next
 * call to banking fetches a fresh token instead of using one issued under the old secret until it expires.
 */
public class BankingTokenCache implements OAuth2AuthorizedClientService {

    private final Map<String, OAuth2AuthorizedClient> clients = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(String clientRegistrationId, String principalName) {
        return (T) clients.get(key(clientRegistrationId, principalName));
    }

    @Override
    public void saveAuthorizedClient(OAuth2AuthorizedClient authorizedClient, Authentication principal) {
        clients.put(key(authorizedClient.getClientRegistration().getRegistrationId(), principal.getName()),
                authorizedClient);
    }

    @Override
    public void removeAuthorizedClient(String clientRegistrationId, String principalName) {
        clients.remove(key(clientRegistrationId, principalName));
    }

    /** Forgets every cached token, so the next call to banking requests a new one. */
    public void evictAll() {
        clients.clear();
    }

    private static String key(String clientRegistrationId, String principalName) {
        return clientRegistrationId + '\u0000' + principalName;
    }
}
