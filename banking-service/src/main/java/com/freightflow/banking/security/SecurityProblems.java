package com.freightflow.banking.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;

/**
 * RFC 9457 problems for what the security chain refuses (ADR 0017): 401 with an RFC 6750 challenge, 403, and a
 * retryable 503 when a token cannot be checked because Keycloak's keys are out of reach. Logged as security events,
 * never with the token.
 */
@Component
public class SecurityProblems {

    private static final Logger events = LoggerFactory.getLogger("freightflow.security");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String BASE = "https://freightflow.dev/problems/";

    public AuthenticationEntryPoint unauthenticated() {
        return (request, response, failure) -> {
            if (failure instanceof AuthenticationServiceException) {
                event(request, "issuer-unavailable", 503, null);
                response.setHeader("Retry-After", "5");
                write(request, response, HttpStatus.SERVICE_UNAVAILABLE, "issuer-unavailable",
                        "Token cannot be verified right now",
                        "The identity provider's signing keys are out of reach. Retry shortly with the same token.");
                return;
            }
            boolean invalid = failure instanceof OAuth2AuthenticationException;
            event(request, "authentication-failed", 401, invalid ? "invalid_token" : "missing_token");
            response.setHeader("WWW-Authenticate", invalid ? "Bearer error=\"invalid_token\"" : "Bearer");
            write(request, response, HttpStatus.UNAUTHORIZED, "unauthenticated", "Authentication required",
                    "This request needs a valid bearer token.");
        };
    }

    public AccessDeniedHandler forbidden() {
        return (request, response, denied) -> {
            event(request, "access-denied", 403, null);
            response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
            write(request, response, HttpStatus.FORBIDDEN, "forbidden", "Forbidden",
                    "Your role does not allow this request.");
        };
    }

    private static void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status, String slug,
                              String title, String detail) throws IOException {
        ObjectNode body = JSON.createObjectNode()
                .put("type", BASE + slug)
                .put("title", title)
                .put("status", status.value())
                .put("detail", detail)
                .put("instance", request.getRequestURI());
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(JSON.writeValueAsBytes(body));
    }

    private static void event(HttpServletRequest request, String event, int status, String reason) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        String clientId = null;
        String subject = null;
        if (authentication instanceof JwtAuthenticationToken token) {
            clientId = token.getToken().getClaimAsString("azp");
            subject = token.getToken().getSubject();
        }
        events.atWarn()
                .addKeyValue("event.action", event)
                .addKeyValue("requestId", MDC.get("requestId"))
                .addKeyValue("client.id", clientId)
                .addKeyValue("user.id", subject)
                .addKeyValue("http.request.method", request.getMethod())
                .addKeyValue("url.path", request.getRequestURI())
                .addKeyValue("http.response.status_code", (Object) status)
                .addKeyValue("reason", (Object) reason)
                .log("Security event {}", event);
    }
}
