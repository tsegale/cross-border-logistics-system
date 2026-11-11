package com.freightflow.gateway.security;

import com.freightflow.gateway.edge.RequestIdFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Locale;

/**
 * Removes every identity header a client could forge before anything else sees the request (ADR 0017). The services
 * never read identity from headers, only from the relayed token they validate themselves; stripping these is the
 * edge's half of that rule, so a service that ever started trusting such a header would still never see a forged one.
 * {@code Authorization} is kept: it is the token the services check.
 */
@Component
public class IdentityHeaderFilter implements WebFilter, Ordered {

    static final List<String> FORGEABLE_PREFIXES = List.of(
            "x-user-", "x-org-", "x-organization-", "x-client-", "x-actor-", "x-roles", "x-auth-",
            "x-authenticated-", "x-forwarded-user", "x-forwarded-email", "x-remote-user", "remote-user");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        boolean forged = exchange.getRequest().getHeaders().headerNames().stream().anyMatch(IdentityHeaderFilter::forgeable);
        if (!forged) {
            return chain.filter(exchange);
        }
        ServerHttpRequest request = exchange.getRequest().mutate().headers(headers -> headers.headerNames().stream()
                .filter(IdentityHeaderFilter::forgeable).toList().forEach(headers::remove)).build();
        return chain.filter(exchange.mutate().request(request).build());
    }

    static boolean forgeable(String header) {
        String name = header.toLowerCase(Locale.ROOT);
        return FORGEABLE_PREFIXES.stream().anyMatch(name::startsWith);
    }

    @Override
    public int getOrder() {
        return RequestIdFilter.ORDER + 2;
    }
}
