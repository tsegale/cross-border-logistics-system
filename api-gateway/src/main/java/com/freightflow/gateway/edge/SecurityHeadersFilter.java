package com.freightflow.gateway.edge;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * The edge's security headers on every response (ADR 0042), whoever writes it: a route, the security chain, or a
 * filter that answers first, such as {@link InternalPathFilter}'s 404, which ZAP found without HSTS. A header is
 * written only where the response has none, so Keycloak's pages keep their own policy.
 * <ul>
 *   <li>HSTS for two years with subdomains: HTTPS only (ADR 0039);</li>
 *   <li>a JSON API's CSP, nothing to render or frame; {@code nosniff}; {@code X-Frame-Options: DENY};</li>
 *   <li>{@code Referrer-Policy: no-referrer}; {@code Cross-Origin-Resource-Policy: same-origin}, so no other site
 *       embeds a response (OWASP REST Security Cheat Sheet);</li>
 *   <li>{@code Cache-Control: no-store}.</li>
 * </ul>
 */
@Component
public class SecurityHeadersFilter implements WebFilter, Ordered {

    static final Map<String, String> HEADERS = Map.of(
            "Strict-Transport-Security", "max-age=63072000 ; includeSubDomains",
            "Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'",
            "X-Content-Type-Options", "nosniff",
            "X-Frame-Options", "DENY",
            "Referrer-Policy", "no-referrer",
            "Cross-Origin-Resource-Policy", "same-origin",
            // Nothing an API answers is for a cache to keep; Keycloak's static resources set their own.
            "Cache-Control", "no-store");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        exchange.getResponse().beforeCommit(() -> {
            HttpHeaders headers = exchange.getResponse().getHeaders();
            HEADERS.forEach((name, value) -> {
                if (!headers.containsHeader(name)) {
                    headers.set(name, value);
                }
            });
            return Mono.empty();
        });
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        // Before everything that may answer on its own, the request id filter included.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
