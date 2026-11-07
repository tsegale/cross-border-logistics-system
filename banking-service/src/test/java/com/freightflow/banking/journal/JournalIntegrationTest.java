package com.freightflow.banking.journal;

import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.idempotency.IdempotencyCleanupJob;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.support.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalIntegrationTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .build();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private IdempotencyService idempotencyService;

    @Autowired
    private IdempotencyCleanupJob cleanupJob;

    @Test
    void appendOnlyTriggerRejectsUpdateAndDeleteOnPostingsAndJournalEntries() throws Exception {
        String accountId = createCustomerAccount("Walvis Bay Exports", "NAD");

        MvcResult depositResult = mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-append-only-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.00,
                                  "currency": "NAD",
                                  "reference": "DEP-APPEND-ONLY"
                                }
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        JsonNode entry = JSON.readTree(depositResult.getResponse().getContentAsByteArray());
        UUID entryId = UUID.fromString(entry.get("id").asString());
        UUID postingId = UUID.fromString(entry.get("postings").get(0).get("id").asString());

        // Attempting UPDATE on postings is rejected by trg_postings_append_only
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE postings SET amount = 9999.0000 WHERE id = ?", postingId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // Attempting DELETE on postings is rejected by trg_postings_append_only
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM postings WHERE id = ?", postingId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // Attempting UPDATE on journal_entries is rejected by trg_journal_entries_append_only
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE journal_entries SET reference = 'TAMPERED' WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        // Attempting DELETE on journal_entries is rejected by trg_journal_entries_append_only
        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM journal_entries WHERE id = ?", entryId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
    }

    @Test
    void deferredConstraintTriggerRejectsUnbalancedOrSinglePostingEntriesAtCommit() {
        UUID clearingId = accountRepository.findByAccountTypeAndCurrency(AccountType.CLEARING, "NAD")
                .orElseThrow().getId();
        UUID revenueId = accountRepository.findByAccountTypeAndCurrency(AccountType.FREIGHT_REVENUE, "NAD")
                .orElseThrow().getId();

        // Unbalanced postings (-100 and +90) rejected at commit
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbcTemplate.update(
                    "INSERT INTO journal_entries (id, entry_type, reference, created_at) VALUES (?, 'TRANSFER', 'BAD-SUM', ?)",
                    entryId, now);
            jdbcTemplate.update(
                    "INSERT INTO postings (id, journal_entry_id, account_id, amount, currency, running_balance, created_at) VALUES (?, ?, ?, -100.0000, 'NAD', -100.0000, ?)",
                    UUID.randomUUID(), entryId, clearingId, now);
            jdbcTemplate.update(
                    "INSERT INTO postings (id, journal_entry_id, account_id, amount, currency, running_balance, created_at) VALUES (?, ?, ?, 90.0000, 'NAD', 90.0000, ?)",
                    UUID.randomUUID(), entryId, revenueId, now);
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        })).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("unbalanced");

        // Single posting rejected at commit
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            UUID entryId = UUID.randomUUID();
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            jdbcTemplate.update(
                    "INSERT INTO journal_entries (id, entry_type, reference, created_at) VALUES (?, 'TRANSFER', 'ONE-POSTING', ?)",
                    entryId, now);
            jdbcTemplate.update(
                    "INSERT INTO postings (id, journal_entry_id, account_id, amount, currency, running_balance, created_at) VALUES (?, ?, ?, 100.0000, 'NAD', 100.0000, ?)",
                    UUID.randomUUID(), entryId, clearingId, now);
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        })).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void depositsReversalsIdempotencyAndKeysetStatementPaginationWorkEndToEnd() throws Exception {
        String accountId = createCustomerAccount("Swakopmund Salt Co", "NAD");

        // Deposit 1: 1,000.00 NAD
        MvcResult dep1 = mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-swakop-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.00,
                                  "currency": "NAD",
                                  "reference": "DEP-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("DEPOSIT"))
                .andExpect(jsonPath("$.postings.length()").value(2))
                .andReturn();
        String dep1Id = JSON.readTree(dep1.getResponse().getContentAsByteArray()).get("id").asString();

        // Replaying with same Idempotency-Key and body returns identical journal entry ID without double-crediting
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-swakop-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 1000.00,
                                  "currency": "NAD",
                                  "reference": "DEP-001"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(dep1Id));

        // Reusing same Idempotency-Key with different amount -> 422
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-swakop-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 2000.00,
                                  "currency": "NAD",
                                  "reference": "DEP-001"
                                }
                                """))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-payload-mismatch"));

        // Missing Idempotency-Key -> 400
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.00,
                                  "currency": "NAD",
                                  "reference": "DEP-NO-KEY"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-missing"));

        // In-flight Idempotency-Key -> 409 with Retry-After
        var inFlightCanonical = Map.of(
                "accountId", accountId,
                "amount", "300.00",
                "currency", "NAD",
                "reference", "DEP-IN-FLIGHT"
        );
        idempotencyService.markInProgress("deposit:" + accountId, "dep-inflight-key", inFlightCanonical);
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-inflight-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 300.00,
                                  "currency": "NAD",
                                  "reference": "DEP-IN-FLIGHT"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/idempotency-key-in-flight"));

        // Deposit 2: 500.00 NAD (running balance = 1,500.00)
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-swakop-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 500.00,
                                  "currency": "NAD",
                                  "reference": "DEP-002"
                                }
                                """))
                .andExpect(status().isCreated());

        // Reverse Deposit 1 (-1,000.00 NAD, running balance = 500.00)
        MvcResult revResult = mockMvc.perform(post("/api/v1/journal-entries/" + dep1Id + "/reversals")
                        .header("Idempotency-Key", "rev-swakop-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Correcting duplicate deposit"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entryType").value("REVERSAL"))
                .andExpect(jsonPath("$.reversesEntryId").value(dep1Id))
                .andReturn();
        String revId = JSON.readTree(revResult.getResponse().getContentAsByteArray()).get("id").asString();

        // GET journal entry by ID
        mockMvc.perform(get("/api/v1/journal-entries/" + revId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversesEntryId").value(dep1Id));

        // Second reversal with a new Idempotency-Key is rejected with 409 journal-entry-already-reversed
        mockMvc.perform(post("/api/v1/journal-entries/" + dep1Id + "/reversals")
                        .header("Idempotency-Key", "rev-swakop-second")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Second attempt"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/journal-entry-already-reversed"));

        // Reversing the reversal entry itself is also rejected with 409
        mockMvc.perform(post("/api/v1/journal-entries/" + revId + "/reversals")
                        .header("Idempotency-Key", "rev-of-rev")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "reason": "Reverse reversal"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/journal-entry-already-reversed"));

        // Account materialised balance is now 500.00 NAD
        mockMvc.perform(get("/api/v1/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value(500.00));

        // Walk keyset-paginated statement 1 item per page (newest first: -1000 -> 500, +500 -> 1500, +1000 -> 1000)
        List<BigDecimal> amounts = new ArrayList<>();
        List<BigDecimal> runningBalances = new ArrayList<>();
        String cursor = null;
        do {
            var req = get("/api/v1/accounts/" + accountId + "/postings").param("size", "1");
            if (cursor != null) {
                req.param("cursor", cursor);
            }
            MvcResult pageRes = mockMvc.perform(req)
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode pageNode = JSON.readTree(pageRes.getResponse().getContentAsByteArray());
            for (JsonNode item : pageNode.get("items")) {
                amounts.add(item.get("amount").decimalValue());
                runningBalances.add(item.get("runningBalance").decimalValue());
            }
            JsonNode nextNode = pageNode.get("nextCursor");
            cursor = (nextNode == null || nextNode.isNull()) ? null : nextNode.asString();
        } while (cursor != null);

        assertThat(amounts).containsExactly(
                new BigDecimal("-1000.00"),
                new BigDecimal("500.00"),
                new BigDecimal("1000.00")
        );
        assertThat(runningBalances).containsExactly(
                new BigDecimal("500.00"),
                new BigDecimal("1500.00"),
                new BigDecimal("1000.00")
        );

        // Verify expired idempotency cleanup job
        jdbcTemplate.update("UPDATE idempotency_keys SET expires_at = now() - interval '1 hour' WHERE idempotency_key = 'dep-inflight-key'");
        assertThat(cleanupJob.purgeExpiredKeys()).isGreaterThanOrEqualTo(1);

        // Verify error responses for currency mismatch, invalid cursor, and unknown journal entry
        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-bad-curr")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.00,
                                  "currency": "EUR",
                                  "reference": "DEP-WRONG-CURR"
                                }
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/currency-mismatch"));

        mockMvc.perform(post("/api/v1/accounts/" + accountId + "/deposits")
                        .header("Idempotency-Key", "dep-bad-scale")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "amount": 100.123,
                                  "currency": "NAD",
                                  "reference": "DEP-BAD-SCALE"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-amount"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/postings")
                        .param("cursor", "invalid-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-cursor"));

        mockMvc.perform(get("/api/v1/accounts/" + accountId + "/postings")
                        .param("size", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/invalid-cursor"));

        mockMvc.perform(get("/api/v1/journal-entries/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/journal-entry-not-found"));
    }

    private String createCustomerAccount(String holder, String currency) throws Exception {
        MvcResult res = mockMvc.perform(post("/api/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "holderReference": "%s",
                                  "currency": "%s"
                                }
                                """.formatted(holder, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(res.getResponse().getContentAsByteArray()).get("id").asString();
    }
}
