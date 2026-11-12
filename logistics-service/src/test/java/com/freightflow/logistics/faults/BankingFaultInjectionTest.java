package com.freightflow.logistics.faults;

import com.freightflow.logistics.banking.BankingTokenCache;
import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.support.SharedContainers;
import com.freightflow.logistics.support.LogisticsFixtures;
import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import eu.rekawek.toxiproxy.model.ToxicDirection;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.context.ImportTestcontainers;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.toxiproxy.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.freightflow.logistics.support.LogisticsFixtures.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Payment saga against a real banking-service (the image compose builds) behind Toxiproxy (ADR 0013). Each test
 * breaks the network or banking in one way, lets the relay run, repairs it, lets the relay run again, and ends by
 * asking banking itself: exactly one hold per shipment, and a balanced ledger.
 *
 * <p>Not an {@code IntegrationTest}: that base class points banking at WireMock, and this one needs the real thing.
 *
 * <p>Since Phase 3 banking validates every token (ADR 0017) and logistics fetches its own from Keycloak (ADR 0019),
 * so a real Keycloak with the repository's realm runs on the same network. Every call needs a fresh token here
 * (refresh skew 299 s on 300 s tokens), so the Keycloak-down test exercises the token request on every attempt.
 *
 * <p>Needs the banking image: {@code docker compose build banking-service} (or {@code -Dbanking.image=...}).
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.minimum-idle=2",
        "freightflow.logistics.payments.relay-enabled=false",
        "spring.http.serviceclient.banking.connect-timeout=500ms",
        "spring.http.serviceclient.banking.read-timeout=3s",
        "freightflow.logistics.banking.retry.max-retries=2",
        "freightflow.logistics.banking.retry.delay=50ms",
        "freightflow.logistics.banking.retry.budget=8s",
        "resilience4j.circuitbreaker.instances.banking.sliding-window-size=4",
        "resilience4j.circuitbreaker.instances.banking.minimum-number-of-calls=4",
        "resilience4j.circuitbreaker.instances.banking.wait-duration-in-open-state=2s",
        "resilience4j.circuitbreaker.instances.banking.permitted-number-of-calls-in-half-open-state=2",
        "freightflow.logistics.banking.token.refresh-skew=299s"
})
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(com.freightflow.logistics.support.TestSecurity.class)
@ImportTestcontainers(SharedContainers.class)
@Testcontainers(disabledWithoutDocker = true)
class BankingFaultInjectionTest {

    private static final String BANKING_IMAGE = System.getProperty("banking.image", "freightflow-banking-service:latest");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Network NETWORK = Network.newNetwork();
    private static final String TEST_OPERATOR = "fault-test-operator";
    private static final String TEST_OPERATOR_SECRET = "fault-test-operator-secret";

    static final dasniko.testcontainers.keycloak.KeycloakContainer KEYCLOAK =
            com.freightflow.logistics.support.RealmKeycloak.container(NETWORK);

    static final PostgreSQLContainer BANKING_DB = new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
            .withNetwork(NETWORK)
            .withNetworkAliases("banking-db")
            .withDatabaseName("banking");

    static final GenericContainer<?> BANKING = new GenericContainer<>(DockerImageName.parse(BANKING_IMAGE))
            .withNetwork(NETWORK)
            .withNetworkAliases("banking-service")
            .withExposedPorts(8081)
            .waitingFor(Wait.forHttp("/actuator/health/readiness").forPort(8081)
                    .withStartupTimeout(Duration.ofMinutes(5)));

    static final ToxiproxyContainer TOXIPROXY = new ToxiproxyContainer(
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.12.0"))
            .withNetwork(NETWORK);

    private static Proxy proxy;

    static {
        KEYCLOAK.start();
        com.freightflow.logistics.support.RealmKeycloak.addOperatorTestClient(KEYCLOAK, TEST_OPERATOR, TEST_OPERATOR_SECRET);
        BANKING_DB.start();
        BANKING.withEnv(Map.of(
                "BANKING_DB_URL", "jdbc:postgresql://banking-db:5432/banking",
                "BANKING_DB_USER", BANKING_DB.getUsername(),
                "BANKING_DB_PASSWORD", BANKING_DB.getPassword(),
                "KEYCLOAK_ISSUER_URI", com.freightflow.logistics.support.RealmKeycloak.ISSUER,
                "KEYCLOAK_JWK_SET_URI", "http://keycloak:8080/realms/freightflow/protocol/openid-connect/certs"));
        BANKING.start();
        TOXIPROXY.start();
        try {
            proxy = new ToxiproxyClient(TOXIPROXY.getHost(), TOXIPROXY.getControlPort())
                    .createProxy("banking", "0.0.0.0:8666", "banking-service:8081");
        } catch (IOException ex) {
            throw new IllegalStateException("Could not create the Toxiproxy proxy", ex);
        }
    }

    @DynamicPropertySource
    static void bankingBehindToxiproxy(DynamicPropertyRegistry registry) {
        registry.add("spring.http.serviceclient.banking.base-url",
                () -> "http://" + TOXIPROXY.getHost() + ":" + TOXIPROXY.getMappedPort(8666));
        registry.add("KEYCLOAK_TOKEN_URI", () -> com.freightflow.logistics.support.RealmKeycloak.tokenUri(KEYCLOAK));
        registry.add("spring.security.oauth2.client.registration.banking.client-secret",
                () -> com.freightflow.logistics.support.RealmKeycloak.LOGISTICS_SECRET);
    }

    @AfterAll
    static void stopNetworkSharers() {
        TOXIPROXY.stop();
        BANKING.stop();
        BANKING_DB.stop();
        KEYCLOAK.stop();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private CircuitBreakerRegistry breakers;

    @Autowired
    private BankingTokenCache bankingTokens;

    /** Talks to banking directly, around the proxy, as an operator: sets accounts up and checks the ledger. */
    private final RestClient banking = RestClient.builder()
            .baseUrl("http://" + BANKING.getHost() + ":" + BANKING.getMappedPort(8081))
            .requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBearerAuth(com.freightflow.logistics.support.RealmKeycloak.clientToken(
                        KEYCLOAK, TEST_OPERATOR, TEST_OPERATOR_SECRET));
                return execution.execute(request, body);
            })
            .build();

    /** Holds are logistics' to read (ADR 0019): its own token. */
    private final RestClient bankingAsLogistics = RestClient.builder()
            .baseUrl("http://" + BANKING.getHost() + ":" + BANKING.getMappedPort(8081))
            .requestInterceptor((request, body, execution) -> {
                request.getHeaders().setBearerAuth(com.freightflow.logistics.support.RealmKeycloak.clientToken(
                        KEYCLOAK, "logistics-service", com.freightflow.logistics.support.RealmKeycloak.LOGISTICS_SECRET));
                return execution.execute(request, body);
            })
            .build();

    private String payerAccount;

    @BeforeEach
    void healthyNetworkAndAFundedPayer() throws IOException {
        for (var toxic : proxy.toxics().getAll()) {
            toxic.remove();
        }
        proxy.enable();
        breakers.circuitBreaker("banking").reset();
        // Commands an earlier test left pending must not be claimed by this one and trip its breaker.
        jdbcTemplate.update("UPDATE shipment_payments SET next_attempt_at = now() + interval '1 day' WHERE status = 'PENDING'");
        JsonNode account = JSON.readTree(banking.post().uri("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .body("{\"currency\":\"EUR\",\"organizationId\":\"0193a0e0-0000-7000-8000-00000000a001\"}")
                .retrieve().body(String.class));
        payerAccount = account.get("accountNumber").asString();
        banking.post().uri("/api/v1/accounts/{id}/deposits", account.get("id").asString())
                .header("Idempotency-Key", "fault-fund-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"amount\":10000.00,\"currency\":\"EUR\",\"reference\":\"FAULT-FUND\"}")
                .retrieve().toBodilessEntity();
    }

    @Test
    void bankingSlowerThanTheReadTimeoutStillEndsWithOneHold() throws Exception {
        String shipment = book();
        // Requests reach banking at once; its answers take longer than logistics waits. Banking commits the hold.
        proxy.toxics().latency("slow-answers", ToxicDirection.DOWNSTREAM, 4_000);

        relay.relayOnce();
        assertThat(shipmentStatus(shipment)).isEqualTo("AWAITING_PAYMENT");
        assertThat(commandStatus(shipment)).isEqualTo("PENDING");

        proxy.toxics().get("slow-answers").remove();
        makeDue(shipment);
        relay.relayOnce();

        assertThat(shipmentStatus(shipment)).isEqualTo("BOOKED");
        assertExactlyOneHoldAndACleanLedger(shipment);
    }

    @Test
    void bankingDownThenBackBooksTheShipmentWithOneHold() throws Exception {
        String shipment = book();
        proxy.disable();

        relay.relayOnce();
        assertThat(commandStatus(shipment)).isEqualTo("PENDING");
        assertThat(holds(shipment)).isEmpty();

        proxy.enable();
        makeDue(shipment);
        relay.relayOnce();

        assertThat(shipmentStatus(shipment)).isEqualTo("BOOKED");
        assertExactlyOneHoldAndACleanLedger(shipment);
    }

    @Test
    void connectionResetAfterBankingCommittedIsReplayedNotDuplicated() throws Exception {
        String shipment = book();
        // Banking reads the request, commits the hold and answers; the proxy cuts the answer after one byte.
        proxy.toxics().limitData("lose-the-answer", ToxicDirection.DOWNSTREAM, 1);

        relay.relayOnce();
        assertThat(shipmentStatus(shipment)).isEqualTo("AWAITING_PAYMENT");
        assertThat(holds(shipment)).as("banking committed the hold before the answer was lost").hasSize(1);

        proxy.toxics().get("lose-the-answer").remove();
        makeDue(shipment);
        relay.relayOnce();

        assertThat(shipmentStatus(shipment)).isEqualTo("BOOKED");
        String holdId = jdbcTemplate.queryForObject("SELECT hold_id::text FROM shipments WHERE id = ?::uuid",
                String.class, shipment);
        assertThat(holds(shipment).getFirst().get("id").asString()).isEqualTo(holdId);
        assertExactlyOneHoldAndACleanLedger(shipment);
    }

    @Test
    void bankingAnswering503UntilItsDatabaseIsBackThenSucceeds() throws Exception {
        String shipment = book();
        BANKING_DB.getDockerClient().pauseContainerCmd(BANKING_DB.getContainerId()).exec();
        try {
            relay.relayOnce();
        } finally {
            BANKING_DB.getDockerClient().unpauseContainerCmd(BANKING_DB.getContainerId()).exec();
        }
        assertThat(commandStatus(shipment)).isEqualTo("PENDING");
        assertThat(lastError(shipment)).containsAnyOf("503", "ResourceAccessException");

        makeDue(shipment);
        relay.relayOnce();

        assertThat(shipmentStatus(shipment)).isEqualTo("BOOKED");
        assertExactlyOneHoldAndACleanLedger(shipment);
    }

    @Test
    void breakerOpensWhileBankingIsDownAndClosesWhenItRecovers() throws Exception {
        List<String> shipments = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            shipments.add(book());
        }
        CircuitBreaker breaker = breakers.circuitBreaker("banking");
        proxy.disable();

        relay.relayOnce();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        // Calls refused by the open breaker never reached banking and did not use up an attempt.
        Integer attempts = jdbcTemplate.queryForObject(
                "SELECT max(attempts) FROM shipment_payments WHERE command = 'AUTHORISE' AND shipment_id = ANY (?::uuid[])",
                Integer.class, (Object) shipments.toArray(new String[0]));
        assertThat(attempts).isLessThanOrEqualTo(1);

        proxy.enable();
        Thread.sleep(2_500);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        // Recovery within a bounded number of breaker cycles: the first trial call can still hit a pooled
        // connection the proxy closed, fail, and re-open the breaker for another wait, which is the breaker doing
        // its job. Half-open permits two trial calls, so the third shipment waits for the breaker to close.
        for (int cycle = 0; cycle < 6 && !allBooked(shipments); cycle++) {
            if (breaker.getState() == CircuitBreaker.State.OPEN) {
                Thread.sleep(2_200);
            }
            for (String shipment : shipments) {
                makeDue(shipment);
            }
            relay.relayOnce();
        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        for (String shipment : shipments) {
            assertThat(shipmentStatus(shipment)).as(() -> jdbcTemplate.queryForMap(
                    "SELECT status, attempts, next_attempt_at, now() AS db_now, last_error FROM shipment_payments "
                            + "WHERE shipment_id = ?::uuid", shipment).toString()).isEqualTo("BOOKED");
            assertExactlyOneHoldAndACleanLedger(shipment);
        }
    }

    @Test
    void keycloakDownWhileLogisticsNeedsAFreshTokenKeepsTheCommandPendingAndLosesNothing() throws Exception {
        String shipment = book();
        // An earlier test may have left a token that is still valid for minutes: the point is a *fresh* one.
        bankingTokens.evictAll();
        var docker = KEYCLOAK.getDockerClient();
        docker.pauseContainerCmd(KEYCLOAK.getContainerId()).exec();
        try {
            for (int run = 0; run < 3; run++) {
                makeDue(shipment);
                relay.relayOnce();
            }
            // No token, so nothing was sent: still pending, and no attempt used up (ADR 0019).
            assertThat(commandStatus(shipment)).isEqualTo("PENDING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempts FROM shipment_payments WHERE shipment_id = ?::uuid AND command = 'AUTHORISE'",
                    Integer.class, shipment)).isZero();
            assertThat(shipmentStatus(shipment)).isEqualTo("AWAITING_PAYMENT");
        } finally {
            docker.unpauseContainerCmd(KEYCLOAK.getContainerId()).exec();
        }
        breakers.circuitBreaker("banking").reset();
        makeDue(shipment);
        relay.relayOnce();

        assertThat(shipmentStatus(shipment)).isEqualTo("BOOKED");
        assertExactlyOneHoldAndACleanLedger(shipment);
    }

    // ------------------------------------------------------------------ helpers

    /** Books a shipment paid from the funded payer: its AUTHORISE command is the only one this test adds. */
    private String book() throws Exception {
        String quote = new LogisticsFixtures(mockMvc).createQuote();
        JsonNode booked = json(mockMvc.perform(post("/api/v1/shipments")
                        .header("Idempotency-Key", "fault-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"quoteId":"%s","shipper":{"name":"Namib Fisheries","address":"Walvis Bay","contact":"x"},
                                 "consignee":{"name":"Hanseatic Seafood","address":"Hamburg","contact":"y"},
                                 "payerAccountNumber":"%s","goodsDescription":"Frozen hake","declaredValue":1000.00,
                                 "plannedPickupDate":"%s","bookingOrganizationId":"0193a0e0-0000-7000-8000-00000000a001"}
                                """.formatted(quote, payerAccount, LogisticsFixtures.defaultPickup())))
                .andExpect(status().isCreated()).andReturn());
        return booked.get("id").asString();
    }

    private boolean allBooked(List<String> shipments) throws Exception {
        for (String shipment : shipments) {
            if (!"BOOKED".equals(shipmentStatus(shipment))) {
                return false;
            }
        }
        return true;
    }

    private String shipmentStatus(String shipment) throws Exception {
        return json(mockMvc.perform(get("/api/v1/shipments/{id}", shipment)).andReturn()).get("status").asString();
    }

    private String commandStatus(String shipment) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM shipment_payments WHERE shipment_id = ?::uuid AND command = 'AUTHORISE'",
                String.class, shipment);
    }

    private String lastError(String shipment) {
        return jdbcTemplate.queryForObject(
                "SELECT coalesce(last_error, '') FROM shipment_payments WHERE shipment_id = ?::uuid AND command = 'AUTHORISE'",
                String.class, shipment);
    }

    private void makeDue(String shipment) {
        jdbcTemplate.update("UPDATE shipment_payments SET next_attempt_at = now() - interval '1 second' "
                + "WHERE shipment_id = ?::uuid AND status = 'PENDING'", shipment);
    }

    private List<JsonNode> holds(String shipment) {
        JsonNode list = JSON.readTree(bankingAsLogistics.get().uri("/api/v1/holds?reference={ref}", "SHIPMENT " + shipment)
                .retrieve().body(String.class));
        List<JsonNode> holds = new ArrayList<>();
        list.forEach(holds::add);
        return holds;
    }

    private void assertExactlyOneHoldAndACleanLedger(String shipment) {
        assertThat(holds(shipment)).as("holds for shipment %s", shipment).hasSize(1);
        JsonNode reconciliation = JSON.readTree(banking.get().uri("/api/v1/ledger/reconciliation")
                .retrieve().body(String.class));
        assertThat(reconciliation.get("balanced").asBoolean()).isTrue();
        assertThat(reconciliation.get("discrepancies").size()).isZero();
    }
}
