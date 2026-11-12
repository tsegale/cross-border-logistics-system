package com.freightflow.logistics.banking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.ClientCredentialsOAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.endpoint.RestClientClientCredentialsTokenResponseClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientHttpServiceGroupConfigurer;

import java.time.Duration;

/**
 * logistics-service's own credentials for banking (ADR 0019): the client-credentials grant against Keycloak, token
 * cached until shortly before it expires, sent on every call to banking. The token request is made inside the banking
 * call, so ADR 0013's breaker, bulkhead and retries cover it; it has its own short timeouts.
 */
@Configuration(proxyBeanMethods = false)
@org.springframework.boot.context.properties.EnableConfigurationProperties(BankingCredentials.TokenSettings.class)
public class BankingCredentials {

    public static final String REGISTRATION = "banking";

    /**
     * @param timeout     connect and read timeout of a token request
     * @param refreshSkew a token is renewed this long before it expires
     */
    @ConfigurationProperties("freightflow.logistics.banking.token")
    public record TokenSettings(Duration timeout, Duration refreshSkew) {
        public TokenSettings {
            timeout = timeout == null ? Duration.ofSeconds(2) : timeout;
            refreshSkew = refreshSkew == null ? Duration.ofSeconds(30) : refreshSkew;
        }
    }

    @Bean
    BankingTokenCache bankingTokenCache() {
        return new BankingTokenCache();
    }

    /** Works outside an HTTP request: the payment relay calls banking from its own threads. */
    @Bean
    OAuth2AuthorizedClientManager bankingClientManager(ClientRegistrationRepository registrations, TokenSettings settings,
                                                       BankingTokenCache bankingTokenCache) {
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(settings.timeout());
        timeouts.setReadTimeout(settings.timeout());
        RestClientClientCredentialsTokenResponseClient tokens = new RestClientClientCredentialsTokenResponseClient();
        tokens.setRestClient(RestClient.builder()
                .messageConverters(converters -> {
                    converters.add(0, new org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter());
                    converters.add(0, new org.springframework.http.converter.FormHttpMessageConverter());
                })
                .defaultStatusHandler(new org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler())
                .requestFactory(timeouts)
                .build());
        ClientCredentialsOAuth2AuthorizedClientProvider provider = new ClientCredentialsOAuth2AuthorizedClientProvider();
        provider.setAccessTokenResponseClient(tokens);
        provider.setClockSkew(settings.refreshSkew());
        AuthorizedClientServiceOAuth2AuthorizedClientManager manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(
                registrations, bankingTokenCache);
        manager.setAuthorizedClientProvider(provider);
        return manager;
    }

    /** Every request of the banking HTTP service group carries logistics' bearer token. */
    @Bean
    RestClientHttpServiceGroupConfigurer bankingBearerToken(OAuth2AuthorizedClientManager bankingClientManager) {
        OAuth2ClientHttpRequestInterceptor interceptor = new OAuth2ClientHttpRequestInterceptor(bankingClientManager);
        interceptor.setClientRegistrationIdResolver(request -> REGISTRATION);
        return groups -> groups.filterByName("banking").forEachClient((group, builder) -> builder.requestInterceptor(interceptor));
    }
}
