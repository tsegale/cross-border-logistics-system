package com.freightflow.gateway.edge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Gives every request an {@code X-Request-Id} (ADR 0015): the client's if it is valid, a new UUID otherwise. It is
 * forwarded upstream, returned on the response, and logged with the outcome of the request. banking and logistics
 * put it in their MDC. Full tracing is Phase 5.
 */
@Component
public class RequestIdFilter implements WebFilter, Ordered {

    public static final String HEADER = "X-Request-Id";
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";
    /** First of the edge filters: everything after it, error responses included, can use the id. */
    public static final int ORDER = Ordered.HIGHEST_PRECEDENCE;
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String incoming = exchange.getRequest().getHeaders().getFirst(HEADER);
        String requestId = incoming != null && VALID.matcher(incoming).matches() ? incoming : UUID.randomUUID().toString();
        ServerHttpRequest request = exchange.getRequest().mutate()
                .headers(headers -> headers.set(HEADER, requestId))
                .build();
        ServerWebExchange withId = exchange.mutate().request(request).build();
        withId.getAttributes().put(ATTRIBUTE, requestId);
        withId.getResponse().getHeaders().set(HEADER, requestId);
        long started = System.nanoTime();
        return chain.filter(withId).doFinally(signal -> {
            try (MDC.MDCCloseable ignored = MDC.putCloseable("requestId", requestId)) {
                log.info("{} {} -> {} in {} ms", request.getMethod(), request.getPath().value(),
                        withId.getResponse().getStatusCode(), (System.nanoTime() - started) / 1_000_000);
            }
        });
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
