package com.freightflow.logistics.dcsa;

import com.freightflow.logistics.telemetry.BoundedGauge;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The fallback for carriers that do not push (ADR 0037): {@code GET <baseUrl>/v2/events} with
 * {@code eventCreatedDateTime:gte} at the newest creation time already seen, the filter DCSA T&amp;T 2.2 defines. The
 * events go into the same inbox as pushed ones, so a carrier that both pushes and is polled never applies an event
 * twice. Polling is our own outbound call to the carrier's API, so there is no signature to check; the cursor and the
 * eventIDs make repeated pages harmless. Each carrier has a breaker ({@code dcsa-poll}); a failure is recorded on the
 * cursor and the {@code freightflow.dcsa.poll.age} gauge shows how long since the last success.
 */
@Component
public class DcsaPoller {

    private static final Logger log = LoggerFactory.getLogger(DcsaPoller.class);
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;
    private static final int PAGE = 100;

    private final DcsaProperties properties;
    private final DcsaInbox inbox;
    private final JdbcTemplate jdbcTemplate;
    private final CircuitBreaker breaker;
    private final RestClient client;
    private final MeterRegistry meters;
    private final Clock clock;

    public DcsaPoller(DcsaProperties properties, DcsaInbox inbox, JdbcTemplate jdbcTemplate,
                      CircuitBreakerRegistry breakers, RestClient.Builder builder, MeterRegistry meters, Clock clock) {
        this.properties = properties;
        this.inbox = inbox;
        this.jdbcTemplate = jdbcTemplate;
        this.breaker = breakers.circuitBreaker("dcsa-poll");
        this.client = builder.clone().requestFactory(ClientHttpRequestFactoryBuilder.jdk()
                        .withHttpClientCustomizer(http -> http.version(HttpClient.Version.HTTP_1_1))
                        .build(HttpClientSettings.defaults().withTimeouts(Duration.ofSeconds(2), Duration.ofSeconds(10))))
                .build();
        this.meters = meters;
        this.clock = clock;
        properties.carriers().forEach((id, carrier) -> {
            if (carrier.mode() == DcsaProperties.Mode.POLL) {
                Gauge.builder("freightflow.dcsa.poll.age", new BoundedGauge(() -> secondsSinceSuccess(id)),
                                BoundedGauge::value)
                        .strongReference(true)
                        .tag("carrier", id)
                        .description("Seconds since the carrier's events were last polled successfully; +Inf never")
                        .baseUnit("seconds")
                        .register(meters);
            }
        });
    }

    @Scheduled(fixedDelayString = "${freightflow.logistics.dcsa.poll-interval:60s}",
            initialDelayString = "${freightflow.logistics.dcsa.poll-initial-delay:10s}")
    public void pollAll() {
        properties.carriers().forEach((id, carrier) -> {
            if (carrier.mode() == DcsaProperties.Mode.POLL && carrier.baseUrl() != null) {
                poll(id, carrier);
            }
        });
    }

    /** One page of one carrier's events; returns how many were new. */
    public int poll(String carrierId, DcsaProperties.Carrier carrier) {
        Instant now = clock.instant();
        Instant after = cursor(carrierId, now);
        try {
            String body = CircuitBreaker.decorateSupplier(breaker, () -> client.get()
                    .uri(carrier.baseUrl().toString() + "/v2/events?eventCreatedDateTime:gte={after}&limit={limit}",
                            Map.of("after", ISO.format(after.atOffset(ZoneOffset.UTC)), "limit", PAGE))
                    .header("API-Version", "2.2.0")
                    .retrieve()
                    .body(String.class)).get();
            List<DcsaInbox.Received> events = DcsaWebhookController.parseOrEmpty(body);
            int stored = 0;
            Instant newest = after;
            for (DcsaInbox.Received event : events) {
                if (inbox.store(carrierId, carrier.subscriptionId(), "POLL", event)) {
                    stored++;
                }
                if (event.createdAt().isAfter(newest)) {
                    newest = event.createdAt();
                }
            }
            jdbcTemplate.update("""
                    INSERT INTO dcsa_poll_cursors (carrier_id, created_after, last_success_at) VALUES (?, ?, ?)
                    ON CONFLICT (carrier_id) DO UPDATE SET created_after = EXCLUDED.created_after,
                        last_success_at = EXCLUDED.last_success_at, last_error = NULL
                    """, carrierId, Timestamp.from(newest), Timestamp.from(now));
            meters.counter("freightflow.dcsa.events.received", "carrier", carrierId, "channel", "poll", "outcome",
                    "stored").increment(stored);
            meters.counter("freightflow.dcsa.polls", "carrier", carrierId, "outcome", "succeeded").increment();
            return stored;
        } catch (RuntimeException ex) {
            jdbcTemplate.update("""
                    INSERT INTO dcsa_poll_cursors (carrier_id, created_after, last_failure_at, last_error) VALUES (?, ?, ?, ?)
                    ON CONFLICT (carrier_id) DO UPDATE SET last_failure_at = EXCLUDED.last_failure_at,
                        last_error = EXCLUDED.last_error
                    """, carrierId, Timestamp.from(after), Timestamp.from(now), truncate(ex.toString()));
            meters.counter("freightflow.dcsa.polls", "carrier", carrierId, "outcome", "failed").increment();
            log.warn("Polling DCSA events of carrier {} failed: {}", carrierId, ex.toString());
            return 0;
        }
    }

    private Instant cursor(String carrierId, Instant now) {
        return jdbcTemplate.query("SELECT created_after FROM dcsa_poll_cursors WHERE carrier_id = ?",
                        (rs, row) -> rs.getTimestamp("created_after").toInstant(), carrierId).stream().findFirst()
                .orElse(now.minus(properties.replayWindow()));
    }

    double secondsSinceSuccess(String carrierId) {
        return jdbcTemplate.query("SELECT last_success_at FROM dcsa_poll_cursors WHERE carrier_id = ?",
                        (rs, row) -> rs.getTimestamp("last_success_at"), carrierId).stream().findFirst()
                .filter(java.util.Objects::nonNull)
                .map(last -> Math.max(0, Duration.between(last.toInstant(), clock.instant()).toMillis() / 1000.0))
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static String truncate(String text) {
        return text.length() <= 300 ? text : text.substring(0, 300);
    }
}
