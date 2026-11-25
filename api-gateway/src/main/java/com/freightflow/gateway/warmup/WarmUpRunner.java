package com.freightflow.gateway.warmup;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.netty.http.client.HttpClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Warms the gateway up before it reports ready, so the first requests after a start don't pay for the key fetch,
 * the first Redis connection, the first connection to each service and the JIT.
 *
 * <p>Every routed request needs a token signed by Keycloak, and the gateway must never mint one, so this is not a
 * replay of real traffic. It runs the parts a first request pays for: a probe token through the
 * {@link ReactiveJwtDecoder} (which fetches the signing keys; the probe itself fails verification, by design), a
 * Redis round trip on the connection the rate limiter uses, a readiness request to every route's service through the
 * gateway's own {@link HttpClient} (its connection pool), and requests to its own port without a valid token, which
 * cross WebFlux, the security filters and the problem rendering on their way to a 401.
 *
 * <p>Spring Boot publishes {@link ReadinessState#ACCEPTING_TRAFFIC} only after every {@link ApplicationRunner} has
 * returned, so the readiness probe, and with it the compose healthcheck {@code docker compose up --wait} waits for,
 * stays down until this is done. A step that fails is logged and the warm-up goes on: warming up must never be the
 * reason the gateway can't start.
 */
@Component
@EnableConfigurationProperties(WarmUpProperties.class)
public class WarmUpRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(WarmUpRunner.class);

    /** RS256 header and an expired claim set with a signature that verifies against no key. */
    static final String PROBE_TOKEN = encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}") + "."
            + encode("{\"sub\":\"warm-up\",\"exp\":1}") + "." + encode("not-a-signature");

    private final ApplicationContext context;
    private final ApplicationAvailability availability;
    private final ReactiveJwtDecoder decoder;
    private final ReactiveStringRedisTemplate redis;
    private final RouteLocator routes;
    private final HttpClient http;
    private final WarmUpProperties properties;
    private final MeterRegistry meters;
    private volatile Result lastResult;

    public WarmUpRunner(ApplicationContext context, ApplicationAvailability availability, ReactiveJwtDecoder decoder,
                        ReactiveStringRedisTemplate redis, RouteLocator routes, HttpClient http,
                        WarmUpProperties properties, MeterRegistry meters) {
        this.context = context;
        this.availability = availability;
        this.decoder = decoder;
        this.redis = redis;
        this.routes = routes;
        this.http = http;
        this.properties = properties;
        this.meters = meters;
    }

    /** What the last warm-up did; null if it didn't run. */
    public Result lastResult() {
        return lastResult;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.enabled()) {
            log.info("Warm-up disabled");
            return;
        }
        ReadinessState readiness = availability.getReadinessState();
        long started = System.nanoTime();
        long deadline = started + properties.timeout().toNanos();
        AtomicInteger steps = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();

        step("signing keys", steps, failed, this::fetchSigningKeys);
        step("redis", steps, failed, () -> await(redis.execute(connection -> connection.ping()).next()));
        List<URI> upstreams = upstreams();
        Integer port = ownPort();
        for (int round = 0; round < properties.rounds(); round++) {
            if (System.nanoTime() > deadline) {
                log.warn("Warm-up stopped at its {} limit after {} steps", properties.timeout(), steps.get());
                break;
            }
            for (URI upstream : upstreams) {
                step(upstream.toString(), steps, failed, () -> get(upstream.resolve("/actuator/health/readiness"), null));
            }
            if (port != null) {
                for (String path : properties.paths()) {
                    // Its own listener is HTTPS; the client trusts the internal CA (httpclient.ssl.ssl-bundle).
                    URI own = URI.create("https://127.0.0.1:" + port + path);
                    step(path, steps, failed, () -> {
                        get(own, "Bearer " + PROBE_TOKEN);
                        get(own, null);
                    });
                }
            }
        }

        long elapsed = System.nanoTime() - started;
        Timer.builder("freightflow.warmup.duration").description("Warm-up before readiness").register(meters)
                .record(elapsed, TimeUnit.NANOSECONDS);
        lastResult = new Result(readiness, steps.get(), failed.get(), upstreams.size(), port != null,
                TimeUnit.NANOSECONDS.toMillis(elapsed));
        log.info("Warm-up finished in {} ms: {} steps, {} failed", lastResult.millis(), steps.get(), failed.get());
    }

    private static void step(String name, AtomicInteger steps, AtomicInteger failed, Runnable action) {
        steps.incrementAndGet();
        try {
            action.run();
        } catch (RuntimeException ex) {
            failed.incrementAndGet();
            log.warn("Warm-up step {} failed: {}", name, ex.toString());
        }
    }

    /** The decoder fetches Keycloak's keys to check the probe's signature, and keeps them for real tokens. */
    private void fetchSigningKeys() {
        Throwable rejected = await(decoder.decode(PROBE_TOKEN).flatMap(accepted -> Mono.<Throwable>empty())
                .onErrorResume(JwtException.class, Mono::just));
        if (rejected == null) {
            throw new IllegalStateException("the warm-up probe token was accepted");
        }
        // The probe always fails: a bad signature once the keys are in, or the reason they could not be fetched.
        log.info("Warm-up probe token rejected, as it should be: {}", rejected.getMessage());
    }

    /** The distinct HTTP services behind the routes. */
    private List<URI> upstreams() {
        List<Route> all = Objects.requireNonNullElse(routes.getRoutes().collectList().block(properties.requestTimeout()),
                List.of());
        return all.stream().map(Route::getUri)
                .filter(uri -> "http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                .map(uri -> URI.create(uri.getScheme() + "://" + uri.getAuthority()))
                .distinct().toList();
    }

    /** Any answer will do: the point is the connection, not the status. */
    private void get(URI uri, String authorization) {
        await(http.headers(headers -> {
                    if (authorization != null) {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                    }
                })
                .get().uri(uri)
                .responseSingle((response, body) -> body.asString(StandardCharsets.UTF_8).defaultIfEmpty("")
                        .thenReturn(response.status().code())));
    }

    private <T> T await(Mono<T> mono) {
        Duration timeout = properties.requestTimeout();
        return mono.block(timeout);
    }

    private Integer ownPort() {
        if (context instanceof WebServerApplicationContext web && web.getWebServer() != null
                && web.getWebServer().getPort() > 0) {
            return web.getWebServer().getPort();
        }
        return null;
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param readinessDuringWarmUp the readiness state while it ran: refusing traffic, by design
     * @param steps                 steps run
     * @param failed                of which failed
     * @param upstreams             distinct services behind the routes
     * @param overHttp              whether the requests to its own port ran (a web server was listening)
     * @param millis                how long it took
     */
    public record Result(ReadinessState readinessDuringWarmUp, int steps, int failed, int upstreams, boolean overHttp,
                         long millis) {
    }
}
