package com.freightflow.gateway.ratelimit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import org.springframework.boot.data.redis.autoconfigure.LettuceClientConfigurationBuilderCustomizer;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import reactor.core.publisher.Mono;

/** Per-caller token buckets on money-moving routes (ADR 0017), as in Smart Tours' ADR 0010. */
@Configuration
public class RateLimitConfig {

    /**
     * {@code caller:<client id>:<subject>}: one budget per person per client, and one per machine client. Money-moving
     * routes are never anonymous (the security chain answers 401 first), so there is no address-based fallback key.
     */
    @Bean
    @Primary
    KeyResolver callerKeyResolver() {
        return exchange -> exchange.getPrincipal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(principal -> ((JwtAuthenticationToken) principal).getToken())
                .map(token -> "caller:" + token.getClaimAsString("azp") + ":" + token.getSubject())
                .switchIfEmpty(Mono.just("caller:anonymous"));
    }

    @Bean
    RateLimitGatewayFilterFactory rateLimitGatewayFilterFactory(RedisRateLimiter redisRateLimiter,
                                                                KeyResolver callerKeyResolver) {
        return new RateLimitGatewayFilterFactory(redisRateLimiter, callerKeyResolver);
    }

    /**
     * When Redis is unreachable, fail the command at once instead of queueing it until the timeout: the limiter then
     * decides fail-open or fail-closed immediately, per route.
     */
    @Bean
    LettuceClientConfigurationBuilderCustomizer failFastWhenRedisIsDown() {
        return builder -> builder.clientOptions(ClientOptions.builder()
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .autoReconnect(true)
                .timeoutOptions(TimeoutOptions.enabled())
                .build());
    }
}
