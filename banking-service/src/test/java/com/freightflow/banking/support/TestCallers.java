package com.freightflow.banking.support;

import com.freightflow.banking.security.Caller;
import com.freightflow.banking.security.SecurityConfig;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

/**
 * The personas of the realm (ADR 0016) as callers for service calls and as tokens for MockMvc requests. Tokens built
 * here go through banking's own role and scope mapping, so a test sees exactly the authorities production does.
 */
public final class TestCallers {

    public static final UUID NAMIB = UUID.fromString("0193a0e0-0000-7000-8000-00000000a001");
    public static final UUID HANSEATIC = UUID.fromString("0193a0e0-0000-7000-8000-00000000a002");

    public static final Caller OPERATOR = new Caller("test-operator", "freightflow-web", null, Set.of("operator"), Set.of());
    public static final Caller AUDITOR = new Caller("test-auditor", "freightflow-web", null, Set.of("auditor"), Set.of());
    public static final Caller CUSTOMER_NAMIB =
            new Caller("test-customer-namib", "freightflow-web", NAMIB, Set.of("customer"), Set.of());
    public static final Caller CUSTOMER_HANSEATIC =
            new Caller("test-customer-hanseatic", "freightflow-web", HANSEATIC, Set.of("customer"), Set.of());
    public static final Caller CARRIER =
            new Caller("test-carrier", "carrier-walvis-haulage", null, Set.of("carrier"), Set.of());
    public static final Caller LOGISTICS =
            new Caller("test-logistics", "logistics-service", null, Set.of(), Set.of(Caller.HOLDS_SCOPE));

    private TestCallers() {
    }

    /** A validated token for this caller, as banking would see it after the resource server checked it. */
    public static RequestPostProcessor as(Caller caller) {
        return jwt().jwt(token -> {
            token.subject(caller.subject())
                    .claim("azp", caller.clientId())
                    .claim("roles", List.copyOf(caller.roles()))
                    .claim("scope", String.join(" ", caller.scopes()));
            if (caller.organizationId() != null) {
                token.claim("organization", Map.of("org", Map.of("id", caller.organizationId().toString())));
            }
        }).authorities(SecurityConfig.authorities());
    }

    public static RequestPostProcessor operator() {
        return as(OPERATOR);
    }

    public static RequestPostProcessor logistics() {
        return as(LOGISTICS);
    }
}
