package com.freightflow.banking.events;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.approval.ApprovalKind;
import com.freightflow.banking.approval.ApprovalService;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.hold.HoldService;
import com.freightflow.banking.hold.dto.CaptureHoldRequest;
import com.freightflow.banking.hold.dto.CreateHoldRequest;
import com.freightflow.banking.hold.dto.ExtendHoldRequest;
import com.freightflow.banking.hold.dto.HoldResponse;
import com.freightflow.banking.support.EventSchemas;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.TestCallers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Producer side of the event contracts (ADR 0023): every event banking writes to its outbox matches the committed
 * schema of its type. The test drives each kind of change through the real services, then checks every row in the
 * outbox, its own and whatever earlier tests left, so no code path writes an event the contract does not allow.
 */
class EventContractIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private HoldService holds;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void everyEventBankingWritesMatchesItsSchema() {
        AccountResponse eur = accounts.createCustomerAccount(TestCallers.CUSTOMER_NAMIB, new CreateAccountRequest("EUR", null));
        AccountResponse nad = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("NAD", TestCallers.NAMIB));
        funding.deposit(eur.accountNumber(), "5000.00", "EUR");
        funding.deposit(nad.accountNumber(), "50000.00", "NAD");

        HoldResponse captured = authorise(eur, "1840.50", "EUR");
        holds.extend(TestCallers.LOGISTICS, captured.id(),
                new ExtendHoldRequest(captured.expiresAt().plus(2, ChronoUnit.DAYS)), key());
        holds.capture(TestCallers.LOGISTICS, captured.id(), new CaptureHoldRequest(null), key());

        HoldResponse crossCurrency = authorise(nad, "120.00", "EUR");
        holds.release(TestCallers.LOGISTICS, crossCurrency.id(), key());

        HoldResponse lapsed = authorise(eur, "10.00", "EUR");
        lapse(lapsed.id());
        assertThat(holds.expire(lapsed.id())).isTrue();

        ApprovalResponse rejected = approvals.request(TestCallers.OPERATOR,
                new CreateApprovalRequest(ApprovalKind.DEPOSIT, eur.accountNumber(), new BigDecimal("99.00"), "EUR",
                        "TYPO", null, null), key());
        approvals.reject(TestCallers.SECOND_OPERATOR, rejected.id(), "Wrong amount");
        ApprovalResponse lapsedApproval = approvals.request(TestCallers.OPERATOR,
                new CreateApprovalRequest(ApprovalKind.DEPOSIT, eur.accountNumber(), new BigDecimal("1.00"), "EUR",
                        "LATE", null, null), key());
        jdbcTemplate.update("""
                UPDATE approval_requests SET requested_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'
                WHERE id = ?""", lapsedApproval.id());
        assertThat(approvals.expire(lapsedApproval.id())).isTrue();

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT event_type, envelope::text AS envelope FROM event_outbox");
        for (Map<String, Object> row : rows) {
            String type = (String) row.get("event_type");
            assertThat(EventSchemas.validate(type, (String) row.get("envelope")))
                    .as("%s: %s", type, row.get("envelope")).isEmpty();
        }

        assertThat(typesOf(eur.id(), nad.id(), captured.id(), crossCurrency.id(), lapsed.id(), rejected.id(),
                lapsedApproval.id()))
                .as("this test produced every type banking publishes, except journal entries (aggregate per entry)")
                .containsAll(Arrays.stream(BankingEvents.Type.values()).map(BankingEvents.Type::typeName)
                        .filter(type -> !type.equals("journal-entry.posted")).toList());
        assertThat(rows).extracting(row -> row.get("event_type")).contains("journal-entry.posted");
    }

    @Test
    void anAggregatesEventsAreNumberedInOrderAndCarryWhoCausedThem() {
        AccountResponse eur = accounts.createCustomerAccount(TestCallers.CUSTOMER_NAMIB, new CreateAccountRequest("EUR", null));
        funding.deposit(eur.accountNumber(), "1000.00", "EUR");
        HoldResponse hold = authorise(eur, "100.00", "EUR");
        holds.extend(TestCallers.LOGISTICS, hold.id(), new ExtendHoldRequest(hold.expiresAt().plus(1, ChronoUnit.DAYS)),
                key());
        holds.capture(TestCallers.LOGISTICS, hold.id(), new CaptureHoldRequest(new BigDecimal("60.00")), key());

        List<JsonNode> events = envelopes(hold.id());
        assertThat(events).extracting(event -> event.path("type").asString())
                .containsExactly("hold.authorised", "hold.extended", "hold.captured");
        assertThat(events).extracting(event -> event.path("sequence").asLong()).containsExactly(1L, 2L, 3L);
        assertThat(events).allSatisfy(event -> {
            assertThat(event.path("aggregateId").asString()).isEqualTo(hold.id().toString());
            assertThat(event.path("actor").path("clientId").asString()).isEqualTo("logistics-service");
            assertThat(event.path("payload").path("hold").path("organizationId").asString())
                    .isEqualTo(TestCallers.NAMIB.toString());
        });
        JsonNode capture = events.get(2).path("payload");
        assertThat(capture.path("capturedAmount").asString()).isEqualTo("60.0000");
        assertThat(capture.path("hold").path("status").asString()).isEqualTo("CAPTURED");
        UUID entryId = UUID.fromString(capture.path("journalEntryId").asString());
        JsonNode posted = envelopes(entryId).getFirst();
        assertThat(posted.path("payload").path("entryType").asString()).isEqualTo("CAPTURE");
        assertThat(posted.path("payload").path("postings").valueStream()
                .map(posting -> new BigDecimal(posting.path("amount").asString()))
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("0");

        JsonNode opened = envelopes(eur.id()).getFirst();
        assertThat(opened.path("actor").path("subject").asString()).isEqualTo("test-customer-namib");
        assertThat(opened.path("actor").path("organizationId").asString()).isEqualTo(TestCallers.NAMIB.toString());
    }

    @Test
    void aReplayedRequestWritesNoSecondEvent() {
        AccountResponse eur = accounts.createCustomerAccount(TestCallers.CUSTOMER_NAMIB, new CreateAccountRequest("EUR", null));
        funding.deposit(eur.accountNumber(), "1000.00", "EUR");
        String key = key();
        CreateHoldRequest request = new CreateHoldRequest(eur.accountNumber(), new BigDecimal("5.00"), "EUR",
                "SHIPMENT " + UUID.randomUUID(), null, TestCallers.NAMIB, null);
        UUID first = holds.createHold(TestCallers.LOGISTICS, request, key).body().id();
        UUID again = holds.createHold(TestCallers.LOGISTICS, request, key).body().id();

        assertThat(again).isEqualTo(first);
        assertThat(envelopes(first)).hasSize(1);
    }

    @Test
    void anExpiredHoldHasNoActor() {
        AccountResponse eur = accounts.createCustomerAccount(TestCallers.CUSTOMER_NAMIB, new CreateAccountRequest("EUR", null));
        funding.deposit(eur.accountNumber(), "1000.00", "EUR");
        HoldResponse hold = authorise(eur, "5.00", "EUR");
        lapse(hold.id());
        holds.expire(hold.id());

        JsonNode expired = envelopes(hold.id()).getLast();
        assertThat(expired.path("type").asString()).isEqualTo("hold.expired");
        assertThat(expired.path("actor").isNull()).isTrue();
        assertThat(expired.path("requestId").isNull()).isTrue();
    }

    private HoldResponse authorise(AccountResponse payer, String amount, String currency) {
        return holds.createHold(TestCallers.LOGISTICS, new CreateHoldRequest(payer.accountNumber(), new BigDecimal(amount),
                currency, "SHIPMENT " + UUID.randomUUID(), Instant.now().plus(3, ChronoUnit.DAYS), TestCallers.NAMIB,
                null), key()).body();
    }

    /** Moves the hold's life into the past, as if its expiry had come. */
    private void lapse(UUID holdId) {
        jdbcTemplate.update("""
                UPDATE holds SET created_at = now() - interval '1 hour', expires_at = now() - interval '1 second'
                WHERE id = ?""", holdId);
    }

    private List<JsonNode> envelopes(UUID aggregateId) {
        return jdbcTemplate.queryForList("SELECT envelope::text FROM event_outbox WHERE aggregate_id = ? ORDER BY sequence",
                String.class, aggregateId).stream().map(JSON::readTree).toList();
    }

    private java.util.Set<String> typesOf(UUID... aggregateIds) {
        return Arrays.stream(aggregateIds).flatMap(id -> envelopes(id).stream())
                .map(event -> event.path("type").asString()).collect(Collectors.toSet());
    }

    private static String key() {
        return "events-" + UUID.randomUUID();
    }
}
