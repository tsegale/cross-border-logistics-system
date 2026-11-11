package com.freightflow.gateway.security;

import com.freightflow.gateway.edge.RequestIdFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ServerWebExchange;

/**
 * Security events as structured log lines (ADR 0017): authentication failures, refusals and rate-limit hits, with the
 * request id, the client id and the subject when they are known. Never the token, and never the reason a token was
 * rejected in more detail than Spring Security's error code: that would help someone forging one.
 */
public final class SecurityEvents {

    private static final Logger log = LoggerFactory.getLogger("freightflow.security");

    private SecurityEvents() {
    }

    public static void authenticationFailed(ServerWebExchange exchange, String reason) {
        event(exchange, "authentication-failed", null, 401, reason);
    }

    public static void accessDenied(ServerWebExchange exchange, Jwt token) {
        event(exchange, "access-denied", token, 403, null);
    }

    public static void rateLimited(ServerWebExchange exchange, Jwt token, String route) {
        event(exchange, "rate-limited", token, 429, route);
    }

    public static void issuerUnavailable(ServerWebExchange exchange) {
        event(exchange, "issuer-unavailable", null, 503, null);
    }

    private static void event(ServerWebExchange exchange, String event, Jwt token, int status, String reason) {
        // Typed locals: addKeyValue also takes a Supplier, and a generic getAttribute would be inferred as one.
        String requestId = exchange.getAttribute(RequestIdFilter.ATTRIBUTE);
        String clientId = token == null ? null : token.getClaimAsString("azp");
        String subject = token == null ? null : token.getSubject();
        log.atWarn()
                .addKeyValue("event.kind", "event")
                .addKeyValue("event.category", "authentication")
                .addKeyValue("event.action", event)
                .addKeyValue("requestId", requestId)
                .addKeyValue("client.id", clientId)
                .addKeyValue("user.id", subject)
                .addKeyValue("http.request.method", exchange.getRequest().getMethod().name())
                .addKeyValue("url.path", exchange.getRequest().getPath().value())
                .addKeyValue("http.response.status_code", (Object) status)
                .addKeyValue("reason", (Object) reason)
                .log("Security event {}", event);
    }
}
