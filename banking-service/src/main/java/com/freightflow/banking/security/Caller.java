package com.freightflow.banking.security;

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
 * Who is calling, read from the token banking validated itself (ADR 0017). Never from a header or a request body.
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
    /** logistics-service's scope for holds (ADR 0019). */
    public static final String HOLDS_SCOPE = "banking.holds";

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

    /** Operators and auditors see every account and entry; only operators change anything. */
    public boolean seesEverything() {
        return hasRole(OPERATOR) || hasRole(AUDITOR);
    }

    public boolean isOperator() {
        return hasRole(OPERATOR);
    }

    public boolean isHoldsClient() {
        return scopes.contains(HOLDS_SCOPE);
    }

    /** The caller's organisation, or 403 for a customer token without one: it may own nothing. */
    public UUID requireOrganization() {
        if (organizationId == null) {
            throw new AccessDeniedException("The token names no customer organisation");
        }
        return organizationId;
    }

    /** True if this caller may see something the organisation owns (null owner: only operators and auditors). */
    public boolean mayAccess(UUID ownerOrganizationId) {
        return seesEverything() || (isCustomer() && organizationId != null && organizationId.equals(ownerOrganizationId));
    }

    /**
     * Idempotency keys are scoped to the caller (ADR 0020): the client plus the organisation, or the subject when there
     * is none. Two customers who pick the same key never collide or see each other's stored response.
     */
    public String idempotencyPartition() {
        return clientId + ":" + (organizationId != null ? "org:" + organizationId : "sub:" + subject);
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
