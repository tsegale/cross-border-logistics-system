package com.freightflow.gateway.security;

import com.freightflow.gateway.edge.Problems;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.oauth2.server.resource.authentication.ReactiveJwtAuthenticationConverterAdapter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.security.web.server.authorization.ServerAccessDeniedHandler;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.time.Clock;
import java.util.List;

/**
 * Authentication at the edge (ADR 0017). Every request needs a valid token except the public tracking view and
 * health; coarse role rules here are a first line, and every service checks the token and the object again itself
 * (ADR 0018). The token is relayed to the service unchanged.
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(SecurityProperties.class)
public class SecurityConfig {

    static final String ROLES_CLAIM = "roles";

    @Bean
    SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http, ReactiveJwtDecoder decoder) {
        ServerAuthenticationEntryPoint unauthenticated = unauthenticated();
        return http
                // Stateless bearer-token API: no cookies, so no CSRF; no sessions to store.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
                .authorizeExchange(exchanges -> exchanges
                        // The public tracking view shows no customer data (ADR 0009).
                        .pathMatchers(HttpMethod.GET, "/api/v1/shipments/track/**").permitAll()
                        // Only served on the management port; on the public port there is no handler, so a 404.
                        .pathMatchers("/actuator/health", "/actuator/health/**", "/actuator/info",
                                "/actuator/prometheus").permitAll()
                        // FreightFlow staff only.
                        .pathMatchers(HttpMethod.POST, "/api/v1/accounts/*/deposits").hasRole("operator")
                        .pathMatchers(HttpMethod.POST, "/api/v1/journal-entries/*/reversals").hasRole("operator")
                        .pathMatchers(HttpMethod.PUT, "/api/v1/shipments/*/carrier").hasRole("operator")
                        .pathMatchers(HttpMethod.POST, "/api/v1/payment-commands/*/retry").hasRole("operator")
                        .pathMatchers("/api/v1/ledger/**", "/api/v1/payment-commands", "/api/v1/payment-commands/**")
                                .hasAnyRole("operator", "auditor")
                        // Carriers report tracking events and read the shipments assigned to them, nothing else.
                        .pathMatchers(HttpMethod.POST, "/api/v1/shipments/*/tracking-events").hasAnyRole("carrier", "operator")
                        .pathMatchers(HttpMethod.GET, "/api/v1/shipments", "/api/v1/shipments/**")
                                .hasAnyRole("customer", "operator", "auditor", "carrier")
                        .pathMatchers(HttpMethod.GET, "/**").hasAnyRole("customer", "operator", "auditor")
                        // Every other change: customers and operators. Auditors change nothing.
                        .anyExchange().hasAnyRole("customer", "operator"))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtDecoder(decoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(accessDenied()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(accessDenied()))
                .build();
    }

    /**
     * Keycloak's keys over the internal network, RS256 only, and the token must name this issuer and this audience
     * and be within its lifetime. Keys are cached with a bounded refresh ({@link CachingJwkSource}).
     */
    @Bean
    ReactiveJwtDecoder jwtDecoder(SecurityProperties properties, Clock clock) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.jwksTimeout().toMillis())
                .responseTimeout(properties.jwksTimeout());
        WebClient keys = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http)).build();
        CachingJwkSource source = new CachingJwkSource(keys, properties.jwkSetUri(), properties.jwksTimeout(),
                properties.jwksMinRefresh(), properties.jwksMaxAge(), clock);
        NimbusReactiveJwtDecoder decoder = NimbusReactiveJwtDecoder.withJwkSource(source)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        // A failed key fetch is not a bad token: surface it as an authentication service failure (503), not a 401.
        return token -> decoder.decode(token).onErrorMap(
                failure -> !(failure instanceof JwtException),
                failure -> new AuthenticationServiceException("Could not obtain the issuer's keys", failure));
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    static OAuth2TokenValidator<Jwt> validator(SecurityProperties properties, Clock clock) {
        JwtTimestampValidator lifetime = new JwtTimestampValidator(properties.clockSkew());
        lifetime.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(
                lifetime,
                new JwtIssuerValidator(properties.issuerUri()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience())));
    }

    /** Maps the {@code roles} claim (["customer"]) to authorities ROLE_customer. */
    static Converter<Jwt, Mono<AbstractAuthenticationToken>> jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return new ReactiveJwtAuthenticationConverterAdapter(converter);
    }

    /**
     * 401 problem with an RFC 6750 challenge: {@code Bearer} when there was no token, {@code Bearer error="invalid_token"}
     * when it was rejected. No error description: why a token failed is not something to tell whoever sent it.
     */
    static ServerAuthenticationEntryPoint unauthenticated() {
        return (exchange, failure) -> {
            if (failure instanceof AuthenticationServiceException) {
                return Mono.error(failure); // the issuer is unreachable: IssuerOutageHandler answers 503
            }
            boolean invalid = failure instanceof org.springframework.security.oauth2.core.OAuth2AuthenticationException;
            SecurityEvents.authenticationFailed(exchange, invalid ? "invalid_token" : "missing_token");
            String challenge = invalid ? "Bearer error=\"invalid_token\"" : "Bearer";
            return Problems.write(exchange, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required",
                    "This request needs a valid bearer token.",
                    headers -> headers.set("WWW-Authenticate", challenge));
        };
    }

    static ServerAccessDeniedHandler accessDenied() {
        return (exchange, denied) -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .doOnNext(token -> SecurityEvents.accessDenied(exchange, token))
                .then(Problems.write(exchange, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                        "Your role does not allow this request.",
                        headers -> headers.set("WWW-Authenticate", "Bearer error=\"insufficient_scope\"")));
    }
}
