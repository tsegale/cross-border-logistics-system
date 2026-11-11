package com.freightflow.gateway.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Who the gateway accepts tokens from (ADR 0017).
 *
 * @param issuerUri          the exact {@code iss} of every token: Keycloak's public URL, not the internal one
 * @param jwkSetUri          where the signing keys are fetched, on the internal network
 * @param audience           the value every token's {@code aud} must contain
 * @param clockSkew          tolerance on {@code exp} and {@code nbf}
 * @param jwksTimeout        connect and response timeout of a key fetch
 * @param jwksMinRefresh     an unknown key id refetches the keys at most this often: a stream of forged key ids
 *                           cannot turn into a stream of requests to Keycloak
 * @param jwksMaxAge         keys older than this are refreshed on the next request; if that fails, they keep working
 */
@ConfigurationProperties("freightflow.security")
public record SecurityProperties(String issuerUri, String jwkSetUri, String audience, Duration clockSkew,
                                 Duration jwksTimeout, Duration jwksMinRefresh, Duration jwksMaxAge) {

    public SecurityProperties {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException("freightflow.security.issuer-uri must be set (KEYCLOAK_ISSUER_URI)");
        }
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            jwkSetUri = issuerUri + "/protocol/openid-connect/certs";
        }
        audience = audience == null || audience.isBlank() ? "api-gateway" : audience;
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
        jwksTimeout = jwksTimeout == null ? Duration.ofSeconds(3) : jwksTimeout;
        jwksMinRefresh = jwksMinRefresh == null ? Duration.ofSeconds(30) : jwksMinRefresh;
        jwksMaxAge = jwksMaxAge == null ? Duration.ofMinutes(10) : jwksMaxAge;
    }
}
