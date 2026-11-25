package com.freightflow.banking.warmup;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.freightflow.banking.tls.InternalTls;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Warms the service up before it reports ready, so the first requests after a start don't pay for the key fetch,
 * the first database connections, Hibernate's first queries, the lazily started DispatcherServlet and the JIT.
 *
 * <p>Every API request needs a token signed by Keycloak, and the service must never mint one, so this is not a
 * replay of real traffic. It runs the parts a first request pays for: a probe token through the {@link JwtDecoder}
 * (which fetches the signing keys; the probe itself fails verification, by design), a read-only query for every
 * entity, and requests to its own port without a valid token, which cross the servlet, the security filters and
 * the problem rendering on their way to a 401.
 *
 * <p>Spring Boot publishes {@link ReadinessState#ACCEPTING_TRAFFIC} only after every {@link ApplicationRunner} has
 * returned, so the readiness probe, and with it the compose healthcheck that {@code docker compose up --wait} and
 * the gateway wait for, stays down until this is done. A step that fails is logged and the warm-up goes on:
 * warming up must never be the reason a service can't start.
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
    private final JwtDecoder decoder;
    private final TransactionTemplate readOnly;
    private final WarmUpProperties properties;
    private final MeterRegistry meters;
    private final InternalTls tls;
    private final boolean ownPortTakesTls;

    @PersistenceContext
    private EntityManager entityManager;

    private volatile Result lastResult;

    public WarmUpRunner(ApplicationContext context, ApplicationAvailability availability, JwtDecoder decoder,
                        PlatformTransactionManager transactions, WarmUpProperties properties, MeterRegistry meters,
                        InternalTls tls, Environment environment) {
        this.context = context;
        this.availability = availability;
        this.decoder = decoder;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.properties = properties;
        this.meters = meters;
        this.tls = tls;
        // Its own port takes mutual TLS wherever server.ssl is on (ADR 0040); the tests that start it without TLS
        // turn it off.
        this.ownPortTakesTls = environment.getProperty("server.ssl.enabled", Boolean.class, true)
                && environment.getProperty("server.ssl.bundle") != null;
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
        RestClient self = selfClient();
        for (int round = 0; round < properties.rounds(); round++) {
            if (System.nanoTime() > deadline) {
                log.warn("Warm-up stopped at its {} limit after {} steps", properties.timeout(), steps.get());
                break;
            }
            step("database", steps, failed, this::queryEveryEntity);
            if (self != null) {
                for (String path : properties.paths()) {
                    step(path, steps, failed, () -> requestWithoutAValidToken(self, path));
                }
            }
        }

        long elapsed = System.nanoTime() - started;
        Timer.builder("freightflow.warmup.duration").description("Warm-up before readiness").register(meters)
                .record(elapsed, TimeUnit.NANOSECONDS);
        lastResult = new Result(readiness, steps.get(), failed.get(), self != null,
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
        try {
            decoder.decode(PROBE_TOKEN);
        } catch (JwtException expected) {
            // The probe always fails: a bad signature once the keys are in, or the reason they could not be fetched.
            log.info("Warm-up probe token rejected, as it should be: {}", expected.getMessage());
            return;
        }
        throw new IllegalStateException("the warm-up probe token was accepted");
    }

    /** One row of every entity: Hibernate's entity loading, the pool's connections and the JDBC statements. */
    private void queryEveryEntity() {
        readOnly.executeWithoutResult(status -> {
            List<EntityType<?>> entities = entityManager.getMetamodel().getEntities().stream()
                    .sorted(Comparator.comparing(EntityType::getName)).toList();
            for (EntityType<?> entity : entities) {
                entityManager.createQuery("select e from " + entity.getName() + " e", entity.getJavaType())
                        .setMaxResults(1).getResultList();
            }
            entityManager.clear();
        });
    }

    private static void requestWithoutAValidToken(RestClient self, String path) {
        self.get().uri(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + PROBE_TOKEN)
                .exchange((request, response) -> response.getStatusCode());
        self.get().uri(path).exchange((request, response) -> response.getStatusCode());
    }

    /**
     * A plain client, not Spring Boot's observed builder: these calls are not outbound traffic and should not appear
     * in {@code http.client.requests}. Null without a web server (MockMvc tests).
     */
    private RestClient selfClient() {
        if (!(context instanceof WebServerApplicationContext web) || web.getWebServer() == null
                || web.getWebServer().getPort() <= 0) {
            return null;
        }
        HttpClient.Builder http = HttpClient.newBuilder().connectTimeout(properties.requestTimeout());
        if (ownPortTakesTls) {
            // As any caller: its own certificate, the internal root, and the name checked (127.0.0.1 is on it).
            http.sslContext(tls.sslContext()).sslParameters(tls.sslParameters());
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http.build());
        factory.setReadTimeout(properties.requestTimeout());
        String origin = (ownPortTakesTls ? "https" : "http") + "://127.0.0.1:" + web.getWebServer().getPort();
        return RestClient.builder().baseUrl(origin).requestFactory(factory).build();
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * @param readinessDuringWarmUp the readiness state while it ran: refusing traffic, by design
     * @param steps                 steps run
     * @param failed                of which failed
     * @param overHttp              whether the HTTP steps ran (a web server was listening)
     * @param millis                how long it took
     */
    public record Result(ReadinessState readinessDuringWarmUp, int steps, int failed, boolean overHttp, long millis) {
    }
}
