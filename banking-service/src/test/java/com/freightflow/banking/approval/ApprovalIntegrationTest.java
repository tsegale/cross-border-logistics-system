package com.freightflow.banking.approval;

import com.freightflow.banking.approval.ApprovalExceptions.ApprovalNotPendingException;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.idempotency.IdempotencyCleanupJob;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.security.Caller;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.TestCallers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The four-eyes principle for deposits and reversals (ADR 0021), through the real security chain and database:
 * {@link TestCallers#OPERATOR} is the maker on every request below unless another caller is set.
 */
class ApprovalIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String PROBLEMS = "https://freightflow.dev/problems/";
    private static final Caller THIRD_OPERATOR =
            new Caller("test-third-operator", "freightflow-web", null, Set.of("operator"), Set.of());

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ApprovalExpirySweeper sweeper;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Autowired
    private Funding funding;

    private String account;

    @BeforeEach
    void account() throws Exception {
        account = json(mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"NAD\",\"organizationId\":\"" + TestCallers.NAMIB + "\"}"))
                .andExpect(status().isCreated()).andReturn()).get("accountNumber").asString();
    }

    @Test
    void aDepositPostsOnlyWhenASecondOperatorApprovesItAndRecordsBoth() throws Exception {
        JsonNode requested = json(requestDeposit("1000.00", key()).andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.account").value(account))
                .andExpect(jsonPath("$.amount").value(1000.00))
                .andExpect(jsonPath("$.requestedBy.subject").value(TestCallers.OPERATOR.subject()))
                .andExpect(jsonPath("$.postedEntryId").doesNotExist())
                .andReturn());
        String id = requested.get("id").asString();
        assertBalance("0.00");

        // The maker can neither approve nor reject their own request.
        mockMvc.perform(post("/api/v1/approvals/{id}/approval", id).header("Idempotency-Key", key()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "four-eyes-required"));
        mockMvc.perform(post("/api/v1/approvals/{id}/rejection", id).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"comment\":\"never mind\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "four-eyes-required"));
        assertBalance("0.00");

        String approvalKey = key();
        JsonNode approved = json(mockMvc.perform(post("/api/v1/approvals/{id}/approval", id)
                        .with(TestCallers.secondOperator()).header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Matches statement line 14\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decidedBy.subject").value(TestCallers.SECOND_OPERATOR.subject()))
                .andExpect(jsonPath("$.decisionComment").value("Matches statement line 14"))
                .andReturn());
        String entry = approved.get("postedEntryId").asString();
        assertBalance("1000.00");

        // The entry's actor is the checker; its approval names the maker.
        mockMvc.perform(get("/api/v1/journal-entries/{id}", entry).with(TestCallers.as(TestCallers.AUDITOR)))
                .andExpect(jsonPath("$.entryType").value("DEPOSIT"))
                .andExpect(jsonPath("$.actor.subject").value(TestCallers.SECOND_OPERATOR.subject()))
                .andExpect(jsonPath("$.approvalId").value(id));
        mockMvc.perform(get("/api/v1/approvals/{id}", id).with(TestCallers.as(TestCallers.AUDITOR)))
                .andExpect(jsonPath("$.requestedBy.subject").value(TestCallers.OPERATOR.subject()))
                .andExpect(jsonPath("$.decidedBy.subject").value(TestCallers.SECOND_OPERATOR.subject()))
                .andExpect(jsonPath("$.postedEntryId").value(entry));

        // The same approval replayed is the same answer; a new one finds it decided. Either way, one entry.
        mockMvc.perform(post("/api/v1/approvals/{id}/approval", id).with(TestCallers.secondOperator())
                        .header("Idempotency-Key", approvalKey)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"Matches statement line 14\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.postedEntryId").value(entry));
        mockMvc.perform(post("/api/v1/approvals/{id}/approval", id).with(TestCallers.as(THIRD_OPERATOR))
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "approval-not-pending"))
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));
        assertBalance("1000.00");
        assertThat(entriesPostedBy(id)).isEqualTo(1);
    }

    @Test
    void aRequestIsIdempotentLikeAnyMoneyMovement() throws Exception {
        String key = key();
        String id = json(requestDeposit("250.00", key).andExpect(status().isCreated()).andReturn()).get("id").asString();
        requestDeposit("250.00", key).andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(id));
        requestDeposit("999.00", key).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-payload-mismatch"));
        mockMvc.perform(post("/api/v1/approvals").contentType(MediaType.APPLICATION_JSON).content(depositBody("1.00")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-missing"));

        Map<String, Object> inFlight = new LinkedHashMap<>();
        inFlight.put("kind", "DEPOSIT");
        inFlight.put("account", account);
        inFlight.put("amount", "300");
        inFlight.put("currency", "NAD");
        inFlight.put("reference", "DEP-APPROVAL");
        idempotencyService.markInProgress("approval", TestCallers.OPERATOR, "approval-in-flight-" + account, inFlight);
        requestDeposit("300.00", "approval-in-flight-" + account).andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value(PROBLEMS + "idempotency-key-in-flight"));

        jdbcTemplate.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 hour' WHERE idempotency_key = ?",
                "approval-in-flight-" + account);
        assertThat(cleanupJob.purgeExpiredKeys()).isGreaterThanOrEqualTo(1);
        assertThat(pendingFor(account)).isEqualTo(1);
    }

    @Test
    void aRequestIsCheckedBeforeAnyoneIsAskedToApproveIt() throws Exception {
        mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DEPOSIT\",\"account\":\"" + account + "\",\"amount\":100.00,"
                                + "\"currency\":\"EUR\",\"reference\":\"WRONG-CURRENCY\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "currency-mismatch"));
        requestDeposit("100.123", key()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-amount"));
        mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DEPOSIT\",\"account\":\"" + account + "\",\"amount\":100.00}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-approval-request"));
        mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"REVERSAL\",\"journalEntryId\":\"" + UUID.randomUUID() + "\",\"amount\":5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-approval-request"));
        mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"REVERSAL\",\"journalEntryId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "journal-entry-not-found"));
        mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"kind\":\"DEPOSIT\",\"account\":\"FF00NAD999999999\",\"amount\":1.00,"
                                + "\"currency\":\"NAD\",\"reference\":\"NOBODY\"}"))
                .andExpect(status().is4xxClientError());
        assertThat(pendingFor(account)).isZero();

        // One open reversal per entry.
        JournalEntryResponse deposit = funding.deposit(account, "40.00", "NAD");
        String reversal = "{\"kind\":\"REVERSAL\",\"journalEntryId\":\"" + deposit.id() + "\",\"reason\":\"Duplicate\"}";
        mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key()).contentType(MediaType.APPLICATION_JSON)
                .content(reversal)).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/approvals").with(TestCallers.secondOperator()).header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON).content(reversal))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "reversal-already-requested"));
    }

    @Test
    void aRejectionNeedsAReasonAndPostsNothing() throws Exception {
        String id = json(requestDeposit("75.00", key()).andReturn()).get("id").asString();
        mockMvc.perform(post("/api/v1/approvals/{id}/rejection", id).with(TestCallers.secondOperator()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "invalid-approval-request"));
        mockMvc.perform(post("/api/v1/approvals/{id}/rejection", id).with(TestCallers.secondOperator())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"comment\":\"No matching bank statement line\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.decidedBy.subject").value(TestCallers.SECOND_OPERATOR.subject()));
        mockMvc.perform(post("/api/v1/approvals/{id}/approval", id).with(TestCallers.as(THIRD_OPERATOR))
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.approvalStatus").value("REJECTED"));
        assertBalance("0.00");
        assertThat(entriesPostedBy(id)).isZero();
    }

    @Test
    void anUnansweredRequestExpiresAndCanNoLongerBeApproved() throws Exception {
        String id = json(requestDeposit("60.00", key()).andReturn()).get("id").asString();
        jdbcTemplate.update("""
                UPDATE approval_requests SET requested_at = now() - interval '2 days', expires_at = now() - interval '1 day'
                WHERE id = ?::uuid
                """, id);

        mockMvc.perform(post("/api/v1/approvals/{id}/approval", id).with(TestCallers.secondOperator())
                        .header("Idempotency-Key", key()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "approval-expired"));

        assertThat(sweeper.sweep()).isGreaterThanOrEqualTo(1);
        mockMvc.perform(get("/api/v1/approvals/{id}", id))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.decidedAt").isNotEmpty())
                .andExpect(jsonPath("$.decidedBy").doesNotExist());
        assertThat(approvals.expire(UUID.fromString(id))).isFalse();
        assertBalance("0.00");
    }

    @Test
    void twoCheckersApprovingAtOnceProduceExactlyOneEntry() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                ApprovalResponse requested = approvals.request(TestCallers.OPERATOR, deposit("10.00"), key());
                CyclicBarrier start = new CyclicBarrier(2);
                List<Future<Object>> outcomes = new ArrayList<>();
                for (Caller checker : List.of(TestCallers.SECOND_OPERATOR, THIRD_OPERATOR)) {
                    outcomes.add(pool.submit(() -> {
                        start.await(10, TimeUnit.SECONDS);
                        try {
                            return approvals.approve(checker, requested.id(), null, key());
                        } catch (ApprovalNotPendingException lost) {
                            return lost;
                        }
                    }));
                }
                List<Object> results = new ArrayList<>();
                for (Future<Object> outcome : outcomes) {
                    results.add(outcome.get(30, TimeUnit.SECONDS));
                }
                assertThat(results).filteredOn(ApprovalResponse.class::isInstance).hasSize(1);
                assertThat(results).filteredOn(ApprovalNotPendingException.class::isInstance).hasSize(1);
                assertThat(entriesPostedBy(requested.id().toString())).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
        assertBalance("50.00");
    }

    @Test
    void theDatabaseRefusesWhatTheServiceWouldNeverDo() {
        // A deposit entry without an approval: no code path may post one on one person's word.
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO journal_entries (id, entry_type, reference, created_at, actor_subject, actor_client_id)
                VALUES (gen_random_uuid(), 'DEPOSIT', 'ALONE', now(), 'rogue', 'freightflow-web')
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no approval");

        // The maker as checker.
        ApprovalResponse requested = approvals.request(TestCallers.OPERATOR, deposit("5.00"), key());
        assertThatThrownBy(() -> jdbcTemplate.update("""
                UPDATE approval_requests SET status = 'REJECTED', decided_at = now(),
                       decided_by_subject = requested_by_subject, decided_by_client_id = requested_by_client_id
                WHERE id = ?
                """, requested.id()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_approval_four_eyes");
    }

    @Test
    void thePendingQueueIsOldestFirstAndFilteredByStatus() throws Exception {
        // The context and its database are shared, and earlier tests leave requests pending: retire them so the
        // queue holds only this test's two, whatever ran before it.
        jdbcTemplate.update("UPDATE approval_requests SET status = 'EXPIRED', decided_at = now() WHERE status = 'PENDING'");
        String first = json(requestDeposit("1.00", key()).andReturn()).get("id").asString();
        String second = json(requestDeposit("2.00", key()).andReturn()).get("id").asString();
        JsonNode pending = json(mockMvc.perform(get("/api/v1/approvals").param("limit", "100")
                .with(TestCallers.as(TestCallers.AUDITOR))).andExpect(status().isOk()).andReturn()).get("items");
        List<String> ids = new ArrayList<>();
        pending.forEach(item -> ids.add(item.get("id").asString()));
        assertThat(ids).containsSubsequence(first, second);
        pending.forEach(item -> assertThat(item.get("status").asString()).isEqualTo("PENDING"));

        mockMvc.perform(get("/api/v1/approvals").param("status", "APPROVED").param("limit", "100"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/approvals").param("limit", "0"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/v1/approvals/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(PROBLEMS + "approval-not-found"));
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions requestDeposit(String amount, String key) throws Exception {
        return mockMvc.perform(post("/api/v1/approvals").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(depositBody(amount)));
    }

    private String depositBody(String amount) {
        return "{\"kind\":\"DEPOSIT\",\"account\":\"" + account + "\",\"amount\":" + amount
                + ",\"currency\":\"NAD\",\"reference\":\"DEP-APPROVAL\"}";
    }

    private CreateApprovalRequest deposit(String amount) {
        return new CreateApprovalRequest(ApprovalKind.DEPOSIT, account, new BigDecimal(amount), "NAD", "DEP-RACE",
                null, null, null);
    }

    private void assertBalance(String expected) throws Exception {
        BigDecimal balance = json(mockMvc.perform(get("/api/v1/accounts/{id}", account)).andReturn())
                .get("ledgerBalance").decimalValue();
        assertThat(balance).isEqualByComparingTo(expected);
    }

    private int entriesPostedBy(String approvalId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM journal_entries WHERE approval_id = ?::uuid", Integer.class, approvalId);
        return count == null ? 0 : count;
    }

    private int pendingFor(String accountNumber) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT count(*) FROM approval_requests ar JOIN accounts a ON a.id = ar.account_id
                WHERE a.account_number = ? AND ar.status = 'PENDING'
                """, Integer.class, accountNumber);
        return count == null ? 0 : count;
    }

    private static String key() {
        return "approval-test-" + UUID.randomUUID();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        return body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
    }
}
