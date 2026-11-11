package com.freightflow.gateway.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webflux.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Every error the gateway produces itself leaves as {@code application/problem+json} (ADR 0015), in the same format
 * as the services. Ordered before Spring Boot's default handler. Problem responses from the services pass through
 * the gateway untouched and never reach this handler.
 */
@Component
@Order(-2)
public class GatewayProblemHandler implements ErrorWebExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GatewayProblemHandler.class);

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        if (exchange.getResponse().isCommitted()) {
            return Mono.error(ex);
        }
        if (isCause(ex, ConnectException.class) || isCause(ex, UnknownHostException.class)
                || isCause(ex, io.netty.channel.ConnectTimeoutException.class)) {
            log.warn("Upstream unavailable for {}: {}", exchange.getRequest().getPath(), ex.getMessage());
            return Problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "upstream-unavailable",
                    "Service unavailable", "The service behind this route cannot be reached right now. Try again shortly.");
        }
        if (isCause(ex, TimeoutException.class)) {
            return upstreamTimeout(exchange);
        }
        if (ex instanceof ResponseStatusException status) {
            return fromStatus(exchange, status.getStatusCode(), status.getReason());
        }
        log.error("Unhandled gateway error for {}", exchange.getRequest().getPath(), ex);
        return Problems.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "The request could not be completed.");
    }

    private Mono<Void> fromStatus(ServerWebExchange exchange, HttpStatusCode code, String reason) {
        HttpStatus status = HttpStatus.resolve(code.value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (status) {
            case NOT_FOUND -> Problems.write(exchange, status, "not-found", "Not found",
                    "No public resource at " + exchange.getRequest().getPath().value());
            case CONTENT_TOO_LARGE -> Problems.write(exchange, status, "request-too-large", "Request too large",
                    reason != null ? reason : "Request body too large");
            case GATEWAY_TIMEOUT -> upstreamTimeout(exchange);
            case SERVICE_UNAVAILABLE -> Problems.write(exchange, status, "upstream-unavailable", "Service unavailable",
                    "The service behind this route cannot be reached right now. Try again shortly.");
            case BAD_GATEWAY -> Problems.write(exchange, status, "upstream-error", "Bad gateway",
                    "The service behind this route answered with an invalid response.");
            case METHOD_NOT_ALLOWED -> Problems.write(exchange, status, "method-not-allowed", "Method not allowed",
                    exchange.getRequest().getMethod() + " is not allowed here");
            default -> status.is4xxClientError()
                    ? Problems.write(exchange, status, "invalid-request", status.getReasonPhrase(),
                            reason != null ? reason : status.getReasonPhrase())
                    : Problems.write(exchange, HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                            "The request could not be completed.");
        };
    }

    private Mono<Void> upstreamTimeout(ServerWebExchange exchange) {
        log.warn("Upstream timeout for {}", exchange.getRequest().getPath());
        return Problems.write(exchange, HttpStatus.GATEWAY_TIMEOUT, "upstream-timeout", "Upstream timeout",
                "The service behind this route did not answer in time. A request that changes data may still "
                        + "have been applied: resend it with the same Idempotency-Key.");
    }

    private static boolean isCause(Throwable ex, Class<? extends Throwable> type) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
