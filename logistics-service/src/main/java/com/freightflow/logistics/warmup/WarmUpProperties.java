package com.freightflow.logistics.warmup;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.List;

/**
 * Warm-up before readiness.
 *
 * @param enabled        off in tests that don't exercise it, and for a quick local start
 * @param rounds         times the database and HTTP steps run: the first loads classes and opens connections,
 *                       the later ones give the JIT the hot paths
 * @param requestTimeout one warm-up HTTP request
 * @param timeout        the warm-up proper, from when its dependencies answer: past this the service goes ready
 *                       anyway, so a slow step can delay readiness but never prevent it
 * @param dependencyTimeout how long to wait for banking and Keycloak first, from the start; a cold start of the
 *                       whole stack takes minutes on a laptop
 * @param paths          requests the service sends itself, without a valid token: they cross the servlet, the
 *                       security filters and the problem rendering, and are answered 401
 */
@ConfigurationProperties("freightflow.warm-up")
public record WarmUpProperties(boolean enabled, int rounds, Duration requestTimeout, Duration timeout,
                               Duration dependencyTimeout, List<String> paths) {

    public WarmUpProperties {
        rounds = Math.max(1, rounds);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        dependencyTimeout = dependencyTimeout == null ? Duration.ofMinutes(4) : dependencyTimeout;
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}
