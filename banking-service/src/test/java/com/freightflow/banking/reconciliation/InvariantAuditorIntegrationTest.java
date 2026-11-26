package com.freightflow.banking.reconciliation;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.reconciliation.dto.InvariantReport;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IsolatedDatabase;
import com.freightflow.banking.support.SharedContainers;
import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.support.TestCertificates;
import com.freightflow.banking.support.TestSecurity;
import com.freightflow.banking.support.TestSpans;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invariant auditor (ADR 0044) passes a ledger that real requests wrote, and names each invariant a corrupted one
 * breaks. The corruption is planted as the database owner with triggers off, as only a bug, a restore or an operator
 * could; on a database of its own, so no other test ever sees it.
 */
@SpringBootTest(properties = {
        "server.ssl.enabled=false",
        "freightflow.tls.mutual=false",
        "freightflow.banking.fx.reference.provider=static",
        // The test audits when it wants to; the schedule never fires during it.
        "freightflow.invariants.initial-delay=1h",
        "freightflow.banking.reconciliation.initial-delay=1h"
})
@AutoConfigureMockMvc
@org.springframework.boot.micrometer.tracing.test.autoconfigure.AutoConfigureTracing
@Import({TestSecurity.class, Funding.class, TestSpans.class})
@Testcontainers(disabledWithoutDocker = true)
class InvariantAuditorIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final IsolatedDatabase DATABASE = IsolatedDatabase.create("invariants");
    private static final String CLEARING_EUR = "01930000-0000-7000-8000-000000000007";

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", DATABASE::url);
        registry.add("spring.datasource.username", () -> "banking_app");
        registry.add("spring.datasource.password", () -> SharedContainers.APP_PASSWORD);
        registry.add("spring.flyway.user", DATABASE::username);
        registry.add("spring.flyway.password", DATABASE::password);
        registry.add("spring.flyway.placeholders.app-password", () -> SharedContainers.APP_PASSWORD);
        registry.add("spring.flyway.placeholders.monitoring-password", () -> "");
        registry.add("spring.kafka.bootstrap-servers", SharedContainers.KAFKA::bootstrapServers);
        registry.add("spring.ssl.bundle.pem.internal.truststore.certificate", TestCertificates::caPem);
        registry.add("spring.ssl.bundle.pem.internal.keystore.certificate",
                () -> TestCertificates.of("banking-service").certificatePem());
        registry.add("spring.ssl.bundle.pem.internal.keystore.private-key",
                () -> TestCertificates.of("banking-service").privateKeyPem());
        registry.add("spring.ssl.bundle.pem.internal.reload-on-update", () -> "false");
        registry.add("KAFKA_USERNAME", () -> "banking");
        registry.add("freightflow.kafka.password", () -> SharedContainers.KAFKA.password("banking"));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private InvariantAuditor auditor;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aLedgerWrittenByRequestsHoldsAndEachPlantedCorruptionIsNamed() throws Exception {
        // Deposits, a capture with duties in it, and the customs settlement: every invariant has rows to check.
        AccountResponse payer = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("EUR", TestCallers.NAMIB));
        funding.deposit(payer.id().toString(), new BigDecimal("5000.00"), "EUR", "SEED");
        String hold = hold(payer, "1200.00");
        mockMvc.perform(post("/api/v1/holds/{id}/capture", hold).with(TestCallers.logistics())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disbursementAmount\":300.00}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/holds/{id}/customs-settlement", hold).with(TestCallers.logistics())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assessedAmount\":310.00,\"authorityReference\":\"26DE470100777777E7\"}"))
                .andExpect(status().isOk());
        // A second, open capture: its duties are still owed, so CUSTOMS_DISBURSEMENTS is not zero.
        String open = hold(payer, "400.00");
        mockMvc.perform(post("/api/v1/holds/{id}/capture", open).with(TestCallers.logistics())
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"disbursementAmount\":150.00}"))
                .andExpect(status().isOk());

        InvariantReport clean = auditor.audit();
        assertThat(clean.broken()).as(clean.toString()).isEmpty();
        // Two captures in EUR, of 1,200.00 and 400.00: what the script compares with logistics' own count.
        assertThat(clean.captures().get("EUR").count()).isEqualTo(2);
        assertThat(clean.captures().get("EUR").amount()).isEqualByComparingTo("1600.00");
        assertThat(clean.invariants()).extracting(InvariantReport.Invariant::name).containsExactly(
                "journal-entries-balance", "balances-reconcile", "holds-captured-once", "customs-disbursements",
                "outbox-in-order");
        // Staff ask through the API; a customer is refused by the service's own rules.
        mockMvc.perform(get("/api/v1/ledger/invariants").with(TestCallers.as(TestCallers.AUDITOR)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.holds").value(true));
        mockMvc.perform(get("/api/v1/ledger/invariants").with(TestCallers.as(TestCallers.CUSTOMER_NAMIB)))
                .andExpect(status().isForbidden());

        UUID entry = UUID.randomUUID();
        asOwnerWithoutTriggers("""
                -- A posting with no counterpart, to the clearing account behind its balance's back.
                INSERT INTO journal_entries (id, entry_type, reference, created_at, actor_subject, actor_client_id)
                VALUES ('%1$s', 'CAPTURE', 'planted', now(), 'test', 'test');
                INSERT INTO postings (id, journal_entry_id, account_id, amount, currency, created_at)
                VALUES (gen_random_uuid(), '%1$s', '%2$s', 1.0000, 'EUR', now());
                -- Duties the open capture never collected.
                UPDATE holds SET captured_disbursement_amount = 140.0000 WHERE id = '%3$s';
                -- An event that never left, with the one before it missing.
                INSERT INTO event_sequences (aggregate_id, last_sequence) VALUES ('%4$s', 3);
                INSERT INTO event_outbox (id, topic, event_type, aggregate_id, sequence, envelope, occurred_at, next_attempt_at)
                VALUES (gen_random_uuid(), 'freightflow.hold.v1', 'hold.captured', '%4$s', 3, '{}', now() - interval '1 hour',
                        now() + interval '1 day');
                """.formatted(entry, CLEARING_EUR, open, UUID.randomUUID()));

        InvariantReport broken = auditor.audit();
        Map<String, Long> violations = broken.invariants().stream()
                .collect(Collectors.toMap(InvariantReport.Invariant::name, InvariantReport.Invariant::violations));
        assertThat(broken.holds()).isFalse();
        assertThat(violations).as(broken.toString()).allSatisfy((name, count) -> assertThat(count).as(name).isPositive());
        assertThat(meters.get("freightflow.invariants.violations").tag("invariant", "holds-captured-once")
                .gauge().value()).isPositive();
        assertThat(broken.invariants()).filteredOn(invariant -> invariant.name().equals("journal-entries-balance"))
                .singleElement().satisfies(invariant -> assertThat(invariant.examples()).singleElement()
                        .asString().contains(entry.toString()));
    }

    private String hold(AccountResponse payer, String amount) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/holds").with(TestCallers.logistics())
                        .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payerOrganizationId":"%s","payerAccount":"%s","amount":%s,"currency":"EUR","reference":"SHIPMENT FF-AUDIT"}
                                """.formatted(TestCallers.NAMIB, payer.accountNumber(), amount)))
                .andExpect(status().isCreated()).andReturn();
        return JSON.readTree(result.getResponse().getContentAsString()).get("id").asString();
    }

    /** The owner, as a restore or a manual fix would write: `replica` turns off the ledger's own triggers. */
    private static void asOwnerWithoutTriggers(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(DATABASE.url(), DATABASE.username(), DATABASE.password());
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("SET LOCAL session_replication_role = replica");
            statement.execute(sql);
            connection.commit();
        }
    }
}
