package com.freightflow.notification.security;

import org.slf4j.MDC;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Who is calling, read from the token notification-service validated itself (ADR 0017). Never from a header or a request body.
 *
 * @param subject        the token's {@code sub}
 * @param clientId       the token's {@code azp}: the OAuth client the caller came through
 * @param organizationId the customer organisation the caller belongs to (Keycloak Organizations, ADR 0016); null for
 *                       FreightFlow staff, carriers and machine clients
 * @param roles          realm roles from the {@code roles} claim
 * @param scopes         OAuth scopes from the {@code scope} claim
 */
public record Caller(String subject, String clientId, UUID organizationId, Set<String> roles, Set<String> scopes) {

    public static final String CUSTOMER = "customer";
    public static final String OPERATOR = "operator";
    public static final String AUDITOR = "auditor";
    public static final String CARRIER = "carrier";

    public Caller {
        roles = Set.copyOf(roles);
        scopes = Set.copyOf(scopes);
    }

    public static Caller from(Jwt jwt) {
        return new Caller(jwt.getSubject(), jwt.getClaimAsString("azp"), organization(jwt), strings(jwt.getClaim("roles")),
                scopes(jwt.getClaimAsString("scope")));
    }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }

    public boolean isCustomer() {
        return hasRole(CUSTOMER);
    }

    /** The caller's organisation, or 403 for a customer token without one: it may own nothing. */
    public UUID requireOrganization() {
        if (organizationId == null) {
            throw new AccessDeniedException("The token names no customer organisation");
        }
        return organizationId;
    }

    /** Who did it, for the audit columns (ADR 0020): this caller and the request id of the current request. */
    public Actor actor() {
        return new Actor(subject, clientId, organizationId, MDC.get("requestId"));
    }

    @SuppressWarnings("unchecked") // the claim is JSON: an object of alias -> {"id": ...}, checked as it is read
    private static UUID organization(Jwt jwt) {
        Object claim = jwt.getClaim("organization");
        if (!(claim instanceof Map<?, ?> organizations) || organizations.size() != 1) {
            return null; // none, or several: a customer of FreightFlow belongs to exactly one (ADR 0016)
        }
        Object details = organizations.values().iterator().next();
        if (details instanceof Map<?, ?> map && map.get("id") instanceof String id) {
            try {
                return UUID.fromString(id);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return null;
    }

    private static Set<String> strings(Object claim) {
        if (claim instanceof Collection<?> values) {
            return values.stream().filter(String.class::isInstance).map(String.class::cast).collect(Collectors.toSet());
        }
        return Set.of();
    }

    private static Set<String> scopes(String scope) {
        return scope == null || scope.isBlank() ? Set.of() : Set.copyOf(List.of(scope.trim().split("\\s+")));
    }
}
