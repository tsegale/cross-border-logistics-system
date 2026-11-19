package com.freightflow.logistics.warmup;

import com.freightflow.logistics.banking.BankingApi;
import com.freightflow.logistics.quote.QuoteService;
import com.freightflow.logistics.quote.dto.CreateQuoteRequest;
import com.freightflow.logistics.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.metamodel.EntityType;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.availability.ApplicationAvailability;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
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
 * the problem rendering on their way to a 401. Then one indicative FX rate from banking, the call every quote makes:
 * it fetches logistics' own client-credentials token from Keycloak and opens the connection to banking. It goes to
 * {@link BankingApi} directly, not through the circuit breaker, so a banking that is still starting cannot open the
 * breaker before the first customer arrives.
 *
 * <p>The quote is the first thing a customer does, and most of its cost is in-process: reading and validating the
 * request, pricing, two inserts and the response. So each round also prices a sample quote through
 * {@link QuoteService} as an internal operator caller, inside a transaction that is always rolled back. That caller
 * never leaves this class: no token is made and no request carries it, so nothing crosses the security boundary,
 * and nothing is committed.
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
    private final TransactionTemplate rolledBack;
    private final WarmUpProperties properties;
    private final MeterRegistry meters;
    private final BankingApi banking;
    private final QuoteService quotes;
    private final JsonMapper json;
    private final Validator validator;
    private volatile boolean bankingAnswered;

    /** An internal caller for the rolled-back sample quote only (see the class comment). */
    private static final Caller WARM_UP = new Caller("warm-up", "logistics-service", null, Set.of(Caller.OPERATOR),
            Set.of());

    /** On the sample quote's package, so a test can tell it would see one that was not rolled back. */
    static final String SAMPLE_MARKER = "warm-up sample";

    private static final String SAMPLE_QUOTE = """
            {"origin":"NAWVB","destination":"DEHAM","mode":"SEA_LCL","incoterm":"FCA","payerRole":"CONSIGNEE",
             "packages":[{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2,
               "description":"warm-up sample"}],
             "indicativeCurrency":"NAD"}
            """;

    @PersistenceContext
    private EntityManager entityManager;

    private volatile Result lastResult;

    public WarmUpRunner(ApplicationContext context, ApplicationAvailability availability, JwtDecoder decoder,
                        PlatformTransactionManager transactions, WarmUpProperties properties, MeterRegistry meters,
                        BankingApi banking, QuoteService quotes, JsonMapper json, Validator validator) {
        this.context = context;
        this.availability = availability;
        this.decoder = decoder;
        this.readOnly = new TransactionTemplate(transactions);
        this.readOnly.setReadOnly(true);
        this.rolledBack = new TransactionTemplate(transactions);
        this.properties = properties;
        this.meters = meters;
        this.banking = banking;
        this.quotes = quotes;
        this.json = json;
        this.validator = validator;
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
        step("banking", steps, failed, () -> untilBankingAnswers(deadline));
        RestClient self = selfClient();
        for (int round = 0; round < properties.rounds(); round++) {
            if (System.nanoTime() > deadline) {
                log.warn("Warm-up stopped at its {} limit after {} steps", properties.timeout(), steps.get());
                break;
            }
            step("database", steps, failed, this::queryEveryEntity);
            step("sample quote", steps, failed, this::priceASampleQuoteAndRollBack);
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

    /**
     * Logistics starts without waiting for banking (quotes work without it), so on a cold start banking may still be
     * starting: ask again each second until it answers or the warm-up's own deadline passes.
     */
    private void untilBankingAnswers(long deadline) {
        while (true) {
            try {
                banking.fxRates("EUR");
                bankingAnswered = true;
                return;
            } catch (RuntimeException notYet) {
                if (System.nanoTime() + TimeUnit.SECONDS.toNanos(1) > deadline) {
                    throw notYet;
                }
                log.info("Warm-up: banking not answering yet ({}), asking again in 1 s", notYet.toString());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw notYet;
                }
            }
        }
    }

    /**
     * Read, validate, price, insert and serialise a quote, then roll it all back. The indicative total, which goes
     * through the circuit breaker, only once banking has answered: a failure here must not count against it.
     */
    private void priceASampleQuoteAndRollBack() {
        CreateQuoteRequest parsed = json.readValue(SAMPLE_QUOTE, CreateQuoteRequest.class);
        if (!validator.validate(parsed).isEmpty()) {
            throw new IllegalStateException("the warm-up sample quote is invalid");
        }
        CreateQuoteRequest request = bankingAnswered ? parsed : new CreateQuoteRequest(parsed.origin(),
                parsed.destination(), parsed.mode(), parsed.incoterm(), parsed.payerRole(), parsed.packages(), null);
        rolledBack.executeWithoutResult(status -> {
            status.setRollbackOnly();
            json.writeValueAsString(quotes.createQuote(WARM_UP, request));
        });
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
        HttpClient http = HttpClient.newBuilder().connectTimeout(properties.requestTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(properties.requestTimeout());
        return RestClient.builder().baseUrl("http://127.0.0.1:" + web.getWebServer().getPort())
                .requestFactory(factory).build();
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
