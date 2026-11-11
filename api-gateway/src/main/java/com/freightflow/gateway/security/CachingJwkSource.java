package com.freightflow.gateway.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The issuer's signing keys, cached (ADR 0017).
 *
 * <ul>
 *   <li>A token whose key id is cached is checked without any request to the issuer, so already-cached keys keep
 *       working while Keycloak is down.</li>
 *   <li>An unknown key id (Keycloak rotated its keys, or the token is forged) refetches the key set, but at most once
 *       per {@code minRefresh}: in between, the unknown key is not found (401) if the last fetch worked, and cannot be
 *       checked (503) if it failed.</li>
 *   <li>Keys older than {@code maxAge} are refreshed on the next request; a failed refresh keeps the cached keys.</li>
 *   <li>If the keys cannot be fetched and the token's key is not cached, the failure surfaces as an error that is not
 *       a JWT error, which the gateway answers with a retryable 503 rather than a 401 (IssuerOutageHandler).</li>
 * </ul>
 */
final class CachingJwkSource implements Function<SignedJWT, Flux<JWK>> {

    private static final Logger log = LoggerFactory.getLogger(CachingJwkSource.class);

    private record Cached(JWKSet keys, Instant fetchedAt) {
    }

    private final WebClient client;
    private final String jwkSetUri;
    private final Duration timeout;
    private final Duration minRefresh;
    private final Duration maxAge;
    private final Clock clock;
    private final AtomicReference<Cached> cached = new AtomicReference<>();
    private final AtomicReference<Instant> lastAttempt = new AtomicReference<>(Instant.EPOCH);
    private final AtomicBoolean lastAttemptFailed = new AtomicBoolean(false);

    CachingJwkSource(WebClient client, String jwkSetUri, Duration timeout, Duration minRefresh, Duration maxAge,
                     Clock clock) {
        this.client = client;
        this.jwkSetUri = jwkSetUri;
        this.timeout = timeout;
        this.minRefresh = minRefresh;
        this.maxAge = maxAge;
        this.clock = clock;
    }

    @Override
    public Flux<JWK> apply(SignedJWT jwt) {
        JWKSelector selector = new JWKSelector(JWKMatcher.forJWSHeader(jwt.getHeader()));
        Cached current = cached.get();
        Instant now = clock.instant();
        boolean stale = current == null || current.fetchedAt().plus(maxAge).isBefore(now);
        List<JWK> found = current == null ? List.of() : selector.select(current.keys());
        if (!found.isEmpty() && !stale) {
            return Flux.fromIterable(found);
        }
        if (!found.isEmpty()) {
            // Stale but usable: refresh, and fall back to what we have if the issuer is unreachable.
            return refresh(now).map(selector::select).onErrorResume(ex -> {
                log.warn("Could not refresh the issuer's keys from {}; using the cached ones: {}", jwkSetUri,
                        ex.toString());
                return Mono.just(found);
            }).flatMapMany(Flux::fromIterable);
        }
        if (current != null && lastAttempt.get().plus(minRefresh).isAfter(now)) {
            if (lastAttemptFailed.get()) {
                return Flux.error(new IllegalStateException("The issuer's keys could not be fetched recently"));
            }
            return Flux.empty(); // refetched recently and it worked: this key id is unknown, not new
        }
        return refresh(now).map(selector::select).flatMapMany(Flux::fromIterable);
    }

    private Mono<JWKSet> refresh(Instant now) {
        lastAttempt.set(now);
        return client.get().uri(jwkSetUri).retrieve().bodyToMono(String.class).timeout(timeout)
                .map(body -> {
                    try {
                        return JWKSet.parse(body);
                    } catch (ParseException ex) {
                        throw new IllegalStateException("The issuer's key set is not valid JSON Web Keys", ex);
                    }
                })
                .doOnNext(keys -> {
                    cached.set(new Cached(keys, clock.instant()));
                    lastAttemptFailed.set(false);
                })
                .doOnError(ex -> lastAttemptFailed.set(true));
    }
}
