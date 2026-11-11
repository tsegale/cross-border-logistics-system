package com.freightflow.logistics.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Whose tokens logistics accepts (ADR 0017).
 *
 * @param issuerUri   the exact {@code iss}: Keycloak's public URL
 * @param jwkSetUri   where the keys are fetched, on the internal network
 * @param audience    the value every token's {@code aud} must contain
 * @param clockSkew   tolerance on {@code exp} and {@code nbf}
 * @param jwksTimeout connect and read timeout of a key fetch
 */
@ConfigurationProperties("freightflow.security")
public record TokenProperties(String issuerUri, String jwkSetUri, String audience, Duration clockSkew,
                              Duration jwksTimeout) {

    public TokenProperties {
        if (issuerUri == null || issuerUri.isBlank()) {
            throw new IllegalStateException("freightflow.security.issuer-uri must be set (KEYCLOAK_ISSUER_URI)");
        }
        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            jwkSetUri = issuerUri + "/protocol/openid-connect/certs";
        }
        audience = audience == null || audience.isBlank() ? "logistics-service" : audience;
        clockSkew = clockSkew == null ? Duration.ofSeconds(30) : clockSkew;
        jwksTimeout = jwksTimeout == null ? Duration.ofSeconds(3) : jwksTimeout;
    }
}
