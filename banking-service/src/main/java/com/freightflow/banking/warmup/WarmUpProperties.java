package com.freightflow.banking.warmup;

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
 * @param timeout        the whole warm-up: past this the service goes ready anyway, so a slow dependency can delay
 *                       readiness but never prevent it
 * @param paths          requests the service sends itself, without a valid token: they cross the servlet, the
 *                       security filters and the problem rendering, and are answered 401
 */
@ConfigurationProperties("freightflow.warm-up")
public record WarmUpProperties(boolean enabled, int rounds, Duration requestTimeout, Duration timeout,
                               List<String> paths) {

    public WarmUpProperties {
        rounds = Math.max(1, rounds);
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(5) : requestTimeout;
        timeout = timeout == null ? Duration.ofSeconds(60) : timeout;
        paths = paths == null ? List.of() : List.copyOf(paths);
    }
}
