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

    /**
     * Whose budget a request spends: the caller's token (the route's own {@code redis-rate-limiter.*} settings), or,
     * for an endpoint without a token (the carriers' webhooks, ADR 0041), the carrier named in the path or the
     * address the request came from, each with its own {@code replenish-rate} and {@code burst}. Several filters on
     * one route, one per key, are several budgets, all of which a request must fit.
     */
    public enum Key { CALLER, CARRIER, SOURCE }

    public static class Config implements org.springframework.cloud.gateway.support.HasRouteId {
        private String routeId;
        private FailMode failMode = FailMode.CLOSED;
        private Key key = Key.CALLER;
        private int replenishRate = 1;
        private int burst = 1;

        @Override
        public void setRouteId(String routeId) {
            this.routeId = routeId;
        }

        @Override
        public String getRouteId() {
            return routeId;
        }

        public FailMode getFailMode() {
            return failMode;
        }

        public void setFailMode(FailMode failMode) {
            this.failMode = failMode;
        }

        public Key getKey() {
            return key;
        }

        public void setKey(Key key) {
            this.key = key;
        }

        public int getReplenishRate() {
            return replenishRate;
        }

        public void setReplenishRate(int replenishRate) {
            this.replenishRate = replenishRate;
        }

        public int getBurst() {
            return burst;
        }

        public void setBurst(int burst) {
            this.burst = burst;
        }
    }

    static final String REFUSED = "freightflow.gateway.rate_limited";
    /** A carrier id as logistics' configuration names them; anything else shares one bucket. */
    private static final java.util.regex.Pattern CARRIER = java.util.regex.Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");

    private static final Logger log = LoggerFactory.getLogger(RateLimitGatewayFilterFactory.class);

    private final RedisRateLimiter rateLimiter;
    private final KeyResolver keyResolver;
    private final io.micrometer.core.instrument.MeterRegistry meters;

    public RateLimitGatewayFilterFactory(RedisRateLimiter rateLimiter, KeyResolver keyResolver,
                                         io.micrometer.core.instrument.MeterRegistry meters) {
        super(Config.class);
        this.rateLimiter = rateLimiter;
        this.keyResolver = keyResolver;
        this.meters = meters;
    }

    @Override
    public GatewayFilter apply(Config config) {
        if (config.getRouteId() != null) {
            // One series per route and key, at zero from the start: a first refusal must read as an increase.
            meters.counter(REFUSED, "route", config.getRouteId(), "key", config.getKey().name().toLowerCase());
        }
        return (exchange, chain) -> {
            Route route = exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            String limit = limitId(route.getId(), config);
            if (config.getKey() != Key.CALLER) {
                rateLimiter.getConfig().computeIfAbsent(limit, id -> new RedisRateLimiter.Config()
                        .setReplenishRate(config.getReplenishRate()).setBurstCapacity(config.getBurst())
                        .setRequestedTokens(1));
            }
            return key(exchange, config)
                    .flatMap(key -> rateLimiter.isAllowed(limit, key)
                            // A failure the limiter does not absorb counts as unavailable too: the fail mode decides.
                            .onErrorResume(ex -> {
                                log.warn("Rate limiter failed on route {}: {}", route.getId(), ex.toString());
                                return Mono.just(new RateLimiter.Response(true,
                                        Map.of(RedisRateLimiter.REMAINING_HEADER, "-1")));
                            })
                            .flatMap(response -> decide(exchange, chain, config, route, limit, response)));
        };
    }

    /** The budget's id: the route's for the caller, {@code <route>.<key>} for the others, so each has its own rate. */
    static String limitId(String routeId, Config config) {
        return config.getKey() == Key.CALLER ? routeId : routeId + "." + config.getKey().name().toLowerCase();
    }

    private Mono<String> key(ServerWebExchange exchange, Config config) {
        return switch (config.getKey()) {
            case CALLER -> keyResolver.resolve(exchange);
            case CARRIER -> {
                String path = exchange.getRequest().getPath().value();
                String carrier = path.substring(path.lastIndexOf('/') + 1);
                yield Mono.just("carrier:" + (CARRIER.matcher(carrier).matches() ? carrier : "invalid"));
            }
            case SOURCE -> Mono.just("source:" + java.util.Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                    .map(address -> address.getAddress().getHostAddress()).orElse("unknown"));
        };
    }


    private Mono<Void> decide(ServerWebExchange exchange, org.springframework.cloud.gateway.filter.GatewayFilterChain chain,
                              Config config, Route route, String limit, RateLimiter.Response response) {
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
        Limits limits = limits(limit);
        long refillSeconds = limits.secondsToRefill(remaining);
        exchange.getResponse().getHeaders().add("RateLimit-Policy",
                "\"" + limit + "\";q=" + limits.burst() + ";w=" + limits.window());
        exchange.getResponse().getHeaders().add("RateLimit",
                "\"" + limit + "\";r=" + remaining + ";t=" + refillSeconds);
        if (response.isAllowed()) {
            return chain.filter(exchange);
        }
        meters.counter(REFUSED, "route", route.getId(), "key", config.getKey().name().toLowerCase()).increment();
        return exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.<Jwt>empty())
                .flatMap(token -> {
                    SecurityEvents.rateLimited(exchange, token.orElse(null), route.getId());
                    return Problems.write(exchange, HttpStatus.TOO_MANY_REQUESTS, "rate-limited", "Too many requests",
                            "This caller's rate limit for " + limit + " is used up. Retry after the "
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
