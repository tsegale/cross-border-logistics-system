package com.freightflow.notification.common;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Puts the gateway's {@code X-Request-Id} (ADR 0015) into the log context as {@code requestId}, so it appears in
 * every ECS log line of the request, and echoes it on the response. A request that did not come through the gateway
 * gets a new id. The id is also on the request's server span as freightflow.request_id (ADR 0030):
 * the trace id follows the work, across threads, the outbox and Kafka; the request id is what a caller
 * quotes.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Request-Id";
    public static final String MDC_KEY = "requestId";
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = valid(request.getHeader(HEADER)) ? request.getHeader(HEADER) : UUID.randomUUID().toString();
        response.setHeader(HEADER, requestId);
        try (MDC.MDCCloseable ignored = MDC.putCloseable(MDC_KEY, requestId)) {
            chain.doFilter(request, response);
        }
    }

    public static boolean valid(String requestId) {
        return requestId != null && VALID.matcher(requestId).matches();
    }
}
