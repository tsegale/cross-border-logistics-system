package com.freightflow.gateway.ratelimit;

import com.freightflow.gateway.edge.Problems;
import com.freightflow.gateway.security.SecurityEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Spring Cloud Gateway's Redis token bucket ({@link RedisRateLimiter}, the Lua script the built-in
 * {@code RequestRateLimiter} runs) with what that filter does not do (ADR 0017):
 * <ul>
 *   <li>a refusal is a 429 problem with {@code Retry-After}, not an empty 429;</li>
 *   <li>the IETF {@code RateLimit-Policy} and {@code RateLimit} fields (draft-ietf-httpapi-ratelimit-headers-11) on
 *       every limited response: {@code "<route>";q=<burst>;w=<seconds to refill a full bucket>} and
 *       {@code "<route>";r=<tokens left>;t=<seconds until the bucket is full again>};</li>
 *   <li>a Redis that cannot be reached is handled per route: {@code fail-mode: open} lets the request through,
 *       {@code closed} answers 503. Money-moving routes fail closed.</li>
 * </ul>
 */
public class RateLimitGatewayFilterFactory extends AbstractGatewayFilterFactory<RateLimitGatewayFilterFactory.Config> {

    public enum FailMode { OPEN, CLOSED }

    public static class Config {
        private FailMode failMode = FailMode.CLOSED;

        public FailMode getFailMode() {
            return failMode;
        }

        public void setFailMode(FailMode failMode) {
            this.failMode = failMode;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(RateLimitGatewayFilterFactory.class);

    private final RedisRateLimiter rateLimiter;
    private final KeyResolver keyResolver;

    public RateLimitGatewayFilterFactory(RedisRateLimiter rateLimiter, KeyResolver keyResolver) {
        super(Config.class);
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
    }

    @Override
    public GatewayFilter apply(Config config) {
        return (exchange, chain) -> {
            Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            return keyResolver.resolve(exchange)
                    .flatMap(key -> rateLimiter.isAllowed(route.getId(), key)
                            // A failure the limiter does not absorb counts as unavailable too: the fail mode decides.
                            .onErrorResume(ex -> {
                                log.warn("Rate limiter failed on route {}: {}", route.getId(), ex.toString());
                                return Mono.just(new RateLimiter.Response(true,
                                        Map.of(RedisRateLimiter.REMAINING_HEADER, "-1")));
                            })
                            .flatMap(response -> decide(exchange, chain, config, route, response)));
        };
    }

    private Mono<Void> decide(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                              Config config, Route route, RateLimiter.Response response) {
        long remaining = Long.parseLong(response.getHeaders().getOrDefault(RedisRateLimiter.REMAINING_HEADER, "-1"));
        if (remaining < 0) {
            if (config.getFailMode() == FailMode.CLOSED) {
                log.warn("Rate limiter unavailable on route {}: failing closed", route.getId());
                return Problems.write(exchange, HttpStatus.SERVICE_UNAVAILABLE, "rate-limiter-unavailable",
                        "Temporarily unavailable",
                        "Money-moving requests are paused while their rate limiter is unavailable. Retry shortly.",
                        headers -> headers.set(HttpHeaders.RETRY_AFTER, "1"));
            }
            return chain.filter(exchange);
        }
        Limits limits = limits(route.getId());
        long refillSeconds = limits.secondsToRefill(remaining);
        exchange.getResponse().getHeaders().set("RateLimit-Policy",
                "\"" + route.getId() + "\";q=" + limits.burst() + ";w=" + limits.window());
        exchange.getResponse().getHeaders().set("RateLimit",
                "\"" + route.getId() + "\";r=" + remaining + ";t=" + refillSeconds);
        if (response.isAllowed()) {
            return chain.filter(exchange);
        }
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.<Jwt>empty())
                .flatMap(token -> {
                    SecurityEvents.rateLimited(exchange, token.orElse(null), route.getId());
                    return Problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                            "This caller's rate limit for " + route.getId() + " is used up. Retry after the "
                                    + "Retry-After interval, with the same Idempotency-Key.",
                            headers -> headers.set(HttpHeaders.RETRY_AFTER, Long.toString(limits.secondsPerToken())));
                });
    }

    record Limits(long burst, long replenishRate, long requestedTokens) {
        long window() {
            return Math.max(1, (long) Math.ceil((double) burst / Math.max(1, replenishRate)));
        }

        long secondsPerToken() {
            return Math.max(1, (long) Math.ceil((double) requestedTokens / Math.max(1, replenishRate)));
        }

        long secondsToRefill(long remaining) {
            return Math.max(1, (long) Math.ceil((double) (burst - remaining) / Math.max(1, replenishRate)));
        }
    }

    Limits limits(String routeId) {
        RedisRateLimiter.Config config = rateLimiter.getConfig().get(routeId);
        if (config == null) {
            return new Limits(1, 1, 1);
        }
        return new Limits(config.getBurstCapacity(), config.getReplenishRate(), config.getRequestedTokens());
    }

    @Override
    public String name() {
        return "RateLimit";
    }
}
