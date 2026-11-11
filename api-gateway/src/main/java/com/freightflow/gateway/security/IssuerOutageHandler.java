package com.freightflow.gateway.security;

import com.freightflow.gateway.edge.Problems;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;

/**
 * The token could not be checked because the issuer's keys are out of reach (ADR 0017). That says nothing about the
 * token, so the answer is a retryable 503 problem rather than a 401, which would tell a client to throw away a valid
 * session. Runs before GatewayProblemHandler, which would answer 500.
 */
@Component
@Order(-3)
class IssuerOutageHandler implements WebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IssuerOutageHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable failure) {
        if (!(failure instanceof AuthenticationServiceException)) {
            return Mono.error(failure);
        }
        log.error("Could not verify a bearer token: {}", failure.getMessage());
        SecurityEvents.issuerUnavailable(exchange);
        return Problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "issuer-unavailable",
                "Token cannot be verified right now",
                "The identity provider's signing keys are out of reach. Retry shortly with the same token.");
    }
}
