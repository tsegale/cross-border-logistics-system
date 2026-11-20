package com.freightflow.notification.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * How notification-service reads a token (ADR 0017): roles, scopes and the one organisation, and which tokens it
 * accepts: its own audience, the realm's issuer, unexpired.
 */
class CallerAndTokenTest {

    private static final String ISSUER = "http://keycloak.test/realms/freightflow";
    private static final UUID ORG = UUID.fromString("0193a0e0-0000-7000-8000-00000000a002");

    @Test
    void aCustomerTokenNamesItsRolesScopesAndOrganisation() {
        Caller caller = Caller.from(jwt(Map.of("roles", List.of("customer", 7), "scope", "openid  profile",
                "organization", Map.of("hanseatic-imports", Map.of("id", ORG.toString())))));

        assertThat(caller.isCustomer()).isTrue();
        assertThat(caller.roles()).containsExactly("customer");
        assertThat(caller.scopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(caller.requireOrganization()).isEqualTo(ORG);
        assertThat(caller.actor().clientId()).isEqualTo("freightflow-web");
        assertThat(SecurityConfig.authorities().convert(jwt(Map.of("roles", List.of("customer"), "scope", "openid"))))
                .extracting(GrantedAuthority::getAuthority).containsExactlyInAnyOrder("ROLE_customer", "SCOPE_openid");
    }

    @Test
    void noneSeveralOrAMalformedOrganisationIsNone() {
        assertThat(Caller.from(jwt(Map.of())).organizationId()).isNull();
        assertThat(Caller.from(jwt(Map.of("organization", Map.of("a", Map.of("id", ORG.toString()),
                "b", Map.of("id", UUID.randomUUID().toString()))))).organizationId()).isNull();
        assertThat(Caller.from(jwt(Map.of("organization", Map.of("a", Map.of("id", "not-a-uuid"))))).organizationId())
                .isNull();
        assertThat(Caller.from(jwt(Map.of("organization", Map.of("a", "flat")))).organizationId()).isNull();
        assertThat(Caller.from(jwt(Map.of("organization", "flat"))).organizationId()).isNull();
        Caller orphan = Caller.from(jwt(Map.of("roles", "customer")));
        assertThat(orphan.roles()).isEmpty();
        assertThatThrownBy(orphan::requireOrganization).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void onlyItsOwnAudienceFromTheRealmWhileValidIsAccepted() {
        TokenProperties properties = new TokenProperties(ISSUER, "http://127.0.0.1:1/certs", null, null, null);
        assertThat(properties.audience()).isEqualTo("notification-service");
        var validator = SecurityConfig.validator(properties, Clock.systemUTC());

        assertThat(validator.validate(token(ISSUER, "notification-service", Instant.now().plusSeconds(60))))
                .extracting(OAuth2TokenValidatorResult::hasErrors).isEqualTo(false);
        assertThat(validator.validate(token(ISSUER, "logistics-service", Instant.now().plusSeconds(60))).hasErrors())
                .isTrue();
        assertThat(validator.validate(token("http://evil.test", "notification-service", Instant.now().plusSeconds(60)))
                .hasErrors()).isTrue();
        assertThat(validator.validate(token(ISSUER, "notification-service", Instant.now().minusSeconds(600)))
                .hasErrors()).isTrue();
    }

    @Test
    void tokenPropertiesHaveDefaultsAndRefuseNoIssuer() {
        TokenProperties properties = new TokenProperties(ISSUER, "http://127.0.0.1:1/certs", "", null, null);
        assertThat(properties.clockSkew()).isPositive();
        assertThat(properties.jwksTimeout()).isLessThanOrEqualTo(Duration.ofSeconds(10));
        assertThatThrownBy(() -> new TokenProperties("", "http://127.0.0.1:1/certs", null, null, null))
                .isInstanceOf(IllegalStateException.class);
        assertThat(new TokenProperties(ISSUER, " ", null, null, null).jwkSetUri())
                .isEqualTo(ISSUER + "/protocol/openid-connect/certs");
    }

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "RS256").subject("someone")
                .claim("azp", "freightflow-web");
        claims.forEach(builder::claim);
        if (claims.isEmpty()) {
            builder.claim("nothing", "here");
        }
        return builder.build();
    }

    private static Jwt token(String issuer, String audience, Instant expiresAt) {
        return Jwt.withTokenValue("t").header("alg", "RS256").subject("someone").issuer(issuer)
                .audience(List.of(audience)).issuedAt(expiresAt.minusSeconds(300)).expiresAt(expiresAt).build();
    }
}
