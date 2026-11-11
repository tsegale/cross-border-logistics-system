package com.freightflow.banking.security;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.client.RestTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * banking validates every token itself (ADR 0017): issuer, its own audience {@code banking-service}, lifetime and
 * RS256, with keys from Keycloak. A request that reaches banking on the compose network without the gateway still
 * needs a valid token. Request rules are coarse; who may touch which account, entry or hold is decided in the
 * services (ADR 0018), where a foreign object is a 404.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(TokenProperties.class)
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder decoder, SecurityProblems problems)
            throws Exception {
        return http
                .csrf(csrf -> csrf.disable()) // bearer tokens, no cookies
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                        // Scraped on the compose network only: the port is not published (ADR 0019).
                        .requestMatchers("/actuator/prometheus").permitAll()
                        // Holds move money for shipments: logistics-service's own token, with its scope (ADR 0019).
                        .requestMatchers("/api/v1/holds", "/api/v1/holds/**").hasAuthority("SCOPE_" + Caller.HOLDS_SCOPE)
                        // Indicative rates: any authenticated caller, logistics' pricing included.
                        .requestMatchers(HttpMethod.GET, "/api/v1/fx-rates").authenticated()
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/deposits").hasRole(Caller.OPERATOR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/journal-entries/*/reversals").hasRole(Caller.OPERATOR)
                        .requestMatchers("/api/v1/ledger/**").hasAnyRole(Caller.OPERATOR, Caller.AUDITOR)
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts", "/api/v1/transfers", "/api/v1/fx-quotes")
                                .hasAnyRole(Caller.CUSTOMER, Caller.OPERATOR)
                        .requestMatchers(HttpMethod.GET, "/**").hasAnyRole(Caller.CUSTOMER, Caller.OPERATOR, Caller.AUDITOR)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(authenticationConverter()))
                        .authenticationEntryPoint(problems.unauthenticated())
                        .accessDeniedHandler(problems.forbidden()))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(problems.unauthenticated())
                        .accessDeniedHandler(problems.forbidden()))
                .build();
    }

    /** In front of Spring Security's filter chain, so it sees the bearer-token filter's rethrown outage. */
    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<IssuerOutageFilter> issuerOutageFilter(
            SecurityProblems problems) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(new IssuerOutageFilter(problems));
        registration.setOrder(-101); // Spring Security's filter chain runs at -100
        return registration;
    }

    @Bean
    JwtDecoder jwtDecoder(TokenProperties properties, org.springframework.beans.factory.ObjectProvider<Clock> clocks) {
        Clock clock = clocks.getIfAvailable(Clock::systemUTC);
        // A hung key endpoint must not hold requests: a key fetch is bounded like any other outbound call.
        SimpleClientHttpRequestFactory timeouts = new SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(properties.jwksTimeout());
        timeouts.setReadTimeout(properties.jwksTimeout());
        RestTemplate keys = new RestTemplate(timeouts);
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(keys)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validator(TokenProperties properties, Clock clock) {
        JwtTimestampValidator lifetime = new JwtTimestampValidator(properties.clockSkew());
        lifetime.setClock(clock);
        return new DelegatingOAuth2TokenValidator<>(lifetime, new JwtIssuerValidator(properties.issuerUri()),
                new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                        aud -> aud != null && aud.contains(properties.audience())));
    }

    /** {@code roles} become ROLE_*, {@code scope} becomes SCOPE_*. */
    public static JwtAuthenticationConverter authenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities());
        return converter;
    }

    public static Converter<Jwt, Collection<GrantedAuthority>> authorities() {
        return jwt -> {
            Caller caller = Caller.from(jwt);
            List<GrantedAuthority> granted = new ArrayList<>();
            caller.roles().forEach(role -> granted.add(new SimpleGrantedAuthority("ROLE_" + role)));
            caller.scopes().forEach(scope -> granted.add(new SimpleGrantedAuthority("SCOPE_" + scope)));
            return granted;
        };
    }
}
