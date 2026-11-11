package com.freightflow.gateway.edge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Internal endpoints answer 404 at the edge whatever the routes say (ADR 0015): banking's holds, logistics' payment
 * commands, and any service's actuator. No route matches them today; this is the second line of defence against a
 * future wildcard route. The gateway's own actuator is on the management port, not here.
 */
@Component
public class InternalPathFilter implements WebFilter, Ordered {

    private final List<String> internalPrefixes;

    public InternalPathFilter(@Value("${freightflow.gateway.internal-path-prefixes:/api/v1/holds,/api/v1/payment-commands,/actuator}")
                              List<String> internalPrefixes) {
        this.internalPrefixes = internalPrefixes.stream().map(p -> p.toLowerCase(Locale.ROOT)).toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().pathWithinApplication().value().toLowerCase(Locale.ROOT);
        for (String prefix : internalPrefixes) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return Problems.write(exchange, HttpStatus.NOT_FOUND, "not-found", "Not found",
                        "No public resource at " + exchange.getRequest().getPath().value());
            }
        }
        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return RequestIdFilter.ORDER + 1;
    }
}
