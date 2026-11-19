package com.freightflow.banking.journal;

import com.freightflow.banking.support.TestCallers;
import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.support.Funding;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
    private Funding funding;

    @Test
    void appendOnlyTriggerRejectsUpdateAndDeleteOnPostingsAndJournalEntries() throws Exception {
        String accountId = createCustomerAccount("Walvis Bay Exports", "NAD");

        JournalEntryResponse entry = funding.deposit(accountId, new BigDecimal("1000.00"), "NAD", "DEP-APPEND-ONLY");
        UUID entryId = entry.id();
        UUID postingId = entry.postings().get(0).id();

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
                    "INSERT INTO journal_entries (id, entry_type, reference, created_at, actor_subject, actor_client_id) VALUES (?, 'TRANSFER', 'BAD-SUM', ?, 'test', 'test')",
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
                    "INSERT INTO journal_entries (id, entry_type, reference, created_at, actor_subject, actor_client_id) VALUES (?, 'TRANSFER', 'ONE-POSTING', ?, 'test', 'test')",
                    entryId, now);
            jdbcTemplate.update(
                    "INSERT INTO postings (id, journal_entry_id, account_id, amount, currency, running_balance, created_at) VALUES (?, ?, ?, 100.0000, 'NAD', 100.0000, ?)",
                    UUID.randomUUID(), entryId, clearingId, now);
            jdbcTemplate.execute("SET CONSTRAINTS ALL IMMEDIATE");
        })).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("at least 2");
    }

    @Test
    void approvedDepositsAndReversalsGiveExactRunningBalancesAndAKeysetStatement() throws Exception {
        String accountId = createCustomerAccount("Swakopmund Salt Co", "NAD");

        JournalEntryResponse dep1 = funding.deposit(accountId, new BigDecimal("1000.00"), "NAD", "DEP-001");
        assertThat(dep1.entryType()).isEqualTo(JournalEntryType.DEPOSIT);
        assertThat(dep1.postings()).hasSize(2);
        funding.deposit(accountId, new BigDecimal("500.00"), "NAD", "DEP-002");

        // Reverse deposit 1 (-1,000.00 NAD, running balance = 500.00)
        JournalEntryResponse reversal = funding.reverse(dep1.id(), "Correcting duplicate deposit");
        assertThat(reversal.entryType()).isEqualTo(JournalEntryType.REVERSAL);
        assertThat(reversal.reversesEntryId()).isEqualTo(dep1.id());

        mockMvc.perform(get("/api/v1/journal-entries/" + reversal.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reversesEntryId").value(dep1.id().toString()))
                .andExpect(jsonPath("$.approvalId").isNotEmpty());

        // Reversing it again, or reversing the reversal, is refused before anyone is asked to approve it
        for (UUID entry : List.of(dep1.id(), reversal.id())) {
            mockMvc.perform(post("/api/v1/approvals")
                            .header("Idempotency-Key", "rev-again-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"kind\":\"REVERSAL\",\"journalEntryId\":\"" + entry + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/journal-entry-already-reversed"));
        }

        // Account materialised balance is now 500.00 NAD
        mockMvc.perform(get("/api/v1/accounts/" + accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerBalance").value(500.00));

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
                                  "organizationId": "%s",
                                  "currency": "%s"
                                }
                                """.formatted(TestCallers.NAMIB, currency)))
                .andExpect(status().isCreated())
                .andReturn();
        return JSON.readTree(res.getResponse().getContentAsByteArray()).get("id").asString();
    }
}
