package com.freightflow.banking.journal;

import com.freightflow.banking.security.Actor;
import com.freightflow.banking.security.Caller;
import com.freightflow.banking.account.Account;
import com.freightflow.banking.account.AccountNotFoundException;
import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.common.LockTimeouts;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.common.UuidOrder;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.journal.JournalEntry.PostingInstruction;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.journal.dto.PostingResponse;
import com.freightflow.banking.journal.dto.ReversalRequest;
import com.freightflow.banking.journal.dto.StatementLineResponse;
import com.freightflow.banking.journal.dto.StatementPageResponse;
import jakarta.persistence.EntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class LedgerService {

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final JournalEntryRepository journalEntries;
    private final PostingRepository postings;
    private final IdempotencyService idempotencyService;
    private final BankingProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final Clock clock;

    public LedgerService(
            AccountService accountService,
            AccountRepository accounts,
            JournalEntryRepository journalEntries,
            PostingRepository postings,
            IdempotencyService idempotencyService,
            BankingProperties properties,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            Clock clock) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.journalEntries = journalEntries;
        this.postings = postings;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    @Transactional
    public JournalEntryResponse deposit(Caller caller, String accountIdOrNumber, DepositRequest request,
                                        String idempotencyKey) {
        Account target = accountService.resolveAccount(accountIdOrNumber);
        if (!target.getAccountType().isCustomer()) {
            throw new InvalidMonetaryAmountException(
                    "Deposits are only permitted into CUSTOMER accounts, not " + target.getAccountType());
        }
        Currency requestedCurrency = MoneyRounding.validateSupportedCurrency(
                request.currency(), properties.normalizedCurrencies());
        if (!target.getCurrency().equals(requestedCurrency.getCurrencyCode())) {
            throw new CurrencyMismatchException(
                    target.getCurrency(),
                    requestedCurrency.getCurrencyCode(),
                    "Deposit currency " + requestedCurrency.getCurrencyCode()
                            + " does not match account currency " + target.getCurrency()
            );
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidMonetaryAmountException("Deposit amount must be positive");
        }
        if (MoneyRounding.hasExcessMinorPrecision(request.amount(), target.getCurrency())) {
            throw new InvalidMonetaryAmountException(
                    "Amount " + request.amount() + " has more than "
                            + MoneyRounding.minorUnits(target.getCurrency()) + " decimal places");
        }
        BigDecimal normalizedAmount = MoneyRounding.normalizeCustomerAmount(request.amount(), target.getCurrency());
        Account clearing = accounts.findByAccountTypeAndCurrency(AccountType.CLEARING, target.getCurrency())
                .orElseThrow(() -> new AccountNotFoundException("CLEARING:" + target.getCurrency()));

        String scope = "deposit:" + target.getId();
        var canonical = Map.of(
                "accountId", target.getId().toString(),
                "amount", MoneyRounding.roundToMinorUnits(normalizedAmount, target.getCurrency()).toPlainString(),
                "currency", target.getCurrency(),
                "reference", request.reference().trim()
        );
        return idempotencyService.execute(
                scope,
                caller,
                idempotencyKey,
                canonical,
                201,
                JournalEntryResponse.class,
                () -> recordEntry(
                        caller.actor(),
                        JournalEntryType.DEPOSIT,
                        request.reference().trim(),
                        null,
                        null,
                        null,
                        List.of(
                                new PostingInstruction(clearing.getId(), normalizedAmount.negate(), target.getCurrency()),
                                new PostingInstruction(target.getId(), normalizedAmount, target.getCurrency())
                        )
                )
        ).body();
    }

    @Transactional
    public JournalEntryResponse reverse(Caller caller, UUID journalEntryId, ReversalRequest request,
                                        String idempotencyKey) {
        String reason = request != null && request.reason() != null && !request.reason().isBlank()
                ? request.reason().trim()
                : "Reversal of " + journalEntryId;
        String scope = "reversal:" + journalEntryId;
        var canonical = Map.of(
                "journalEntryId", journalEntryId.toString(),
                "reason", reason
        );
        return idempotencyService.execute(
                scope,
                caller,
                idempotencyKey,
                canonical,
                201,
                JournalEntryResponse.class,
                () -> executeReversal(caller, journalEntryId, reason)
        ).body();
    }

    private JournalEntryResponse executeReversal(Caller caller, UUID journalEntryId, String reason) {
        JournalEntry original = journalEntries.findById(journalEntryId)
                .orElseThrow(() -> new JournalEntryNotFoundException(journalEntryId));
        if (original.getEntryType() == JournalEntryType.REVERSAL) {
            throw new JournalEntryAlreadyReversedException(
                    journalEntryId, "Reversal entry " + journalEntryId + " cannot itself be reversed");
        }
        if (journalEntries.existsByReversesEntryId(journalEntryId)) {
            throw new JournalEntryAlreadyReversedException(
                    journalEntryId, "Journal entry " + journalEntryId + " has already been reversed");
        }
        List<Posting> originalPostings = postings.findByJournalEntryIdOrderByIdAsc(journalEntryId);
        List<PostingInstruction> oppositeInstructions = originalPostings.stream()
                .map(p -> new PostingInstruction(p.getAccountId(), p.getAmount().negate(), p.getCurrency()))
                .toList();
        try {
            return recordEntry(
                    caller.actor(),
                    JournalEntryType.REVERSAL,
                    reason,
                    original.getId(),
                    original.getFxQuoteId(),
                    original.getFxRate(),
                    oppositeInstructions
            );
        } catch (DataIntegrityViolationException ex) {
            throw new JournalEntryAlreadyReversedException(
                    journalEntryId, "Journal entry " + journalEntryId + " has already been reversed");
        }
    }

    /**
     * Appends a balanced journal entry (ADR 0004).
     *
     * <p>Customer accounts are locked with {@code SELECT ... FOR UPDATE} in ascending id order (ADR 0005), checked
     * and updated in place, because their balance may not go negative. System accounts (CLEARING, FX_POSITION,
     * FREIGHT_REVENUE) are applied according to {@link SystemPostingMode} (ADR 0010): in UNLOCKED and SHARDED
     * mode their row is never locked, so they stop serialising every deposit, FX transfer and capture.
     */
    @Transactional
    public JournalEntryResponse recordEntry(
            Actor actor,
            JournalEntryType entryType,
            String reference,
            UUID reversesEntryId,
            UUID fxQuoteId,
            BigDecimal fxRate,
            List<PostingInstruction> instructions) {
        JournalEntry.assertBalanced(instructions);
        SystemPostingMode mode = properties.systemAccounts().postingMode();

        Set<UUID> accountIds = instructions.stream()
                .map(PostingInstruction::accountId)
                .collect(Collectors.toSet());
        // Evict pre-lock Account snapshots from the 1st-level cache so the locking SELECT hydrates the latest
        // committed balances.
        entityManager.flush();
        entityManager.clear();
        Map<UUID, AccountType> types = accountTypes(accountIds);
        if (types.size() != accountIds.size()) {
            throw new AccountNotFoundException("One or more accounts in journal entry were not found");
        }
        Set<UUID> lockIds = mode == SystemPostingMode.LOCKED
                ? accountIds
                : accountIds.stream().filter(id -> types.get(id).isCustomer()).collect(Collectors.toSet());
        Set<UUID> unlockedIds = accountIds.stream().filter(id -> !lockIds.contains(id)).collect(Collectors.toSet());

        Map<UUID, Account> accountsById = new java.util.HashMap<>();
        if (!lockIds.isEmpty()) {
            LockTimeouts.withLockTimeout(jdbcTemplate, properties.ledger().accountLockTimeout(),
                    () -> accounts.findAllByIdInForUpdate(lockIds)).forEach(a -> accountsById.put(a.getId(), a));
        }
        if (!unlockedIds.isEmpty()) {
            accounts.findAllById(unlockedIds).forEach(a -> accountsById.put(a.getId(), a));
        }

        Instant now = clock.instant();
        JournalEntry entry = journalEntries.save(
                JournalEntry.create(entryType, reference, reversesEntryId, fxQuoteId, fxRate, now).recordedBy(actor));
        journalEntries.flush();
        int shard = Math.floorMod(entry.getId().hashCode(), properties.systemAccounts().shards());

        // Shard rows are locked after the account rows and in ascending account id, so the global lock order
        // (accounts by id, then shards by account id) never inverts between two entries.
        List<PostingInstruction> ordered = instructions.stream()
                .sorted(java.util.Comparator.comparing(PostingInstruction::accountId, UuidOrder.POSTGRES))
                .toList();
        Map<PostingInstruction, PostingResponse> responses = new java.util.IdentityHashMap<>();
        for (PostingInstruction instruction : ordered) {
            Account account = accountsById.get(instruction.accountId());
            if (!account.getCurrency().equalsIgnoreCase(instruction.currency())) {
                throw new CurrencyMismatchException(
                        account.getCurrency(),
                        instruction.currency(),
                        "Posting currency " + instruction.currency()
                                + " does not match account currency " + account.getCurrency());
            }
            Posting posting;
            if (lockIds.contains(account.getId())) {
                BigDecimal runningBalance = account.applyPosting(instruction.signedAmount(), now);
                posting = Posting.create(entry.getId(), account.getId(), instruction.signedAmount(),
                        account.getCurrency(), runningBalance, now);
            } else if (mode == SystemPostingMode.SHARDED) {
                account.assertActive();
                BigDecimal shardBalance = applyToShard(account.getId(), shard, instruction.signedAmount(), now);
                posting = Posting.sharded(entry.getId(), account.getId(), instruction.signedAmount(),
                        account.getCurrency(), shard, shardBalance, now);
            } else {
                account.assertActive();
                posting = Posting.unlocked(entry.getId(), account.getId(), instruction.signedAmount(),
                        account.getCurrency(), now);
            }
            responses.put(instruction, PostingResponse.from(postings.save(posting), account.getAccountNumber()));
        }
        postings.flush();
        List<PostingResponse> postingResponses = instructions.stream().map(responses::get).toList();
        return JournalEntryResponse.from(entry, postingResponses);
    }

    private Map<UUID, AccountType> accountTypes(Set<UUID> ids) {
        Map<UUID, AccountType> types = new java.util.HashMap<>();
        jdbcTemplate.query(
                "SELECT id, account_type FROM accounts WHERE id = ANY (?)",
                rs -> {
                    types.put(rs.getObject("id", UUID.class), AccountType.valueOf(rs.getString("account_type")));
                },
                (Object) ids.toArray(new UUID[0]));
        return types;
    }

    private BigDecimal applyToShard(UUID accountId, int shard, BigDecimal signedAmount, Instant now) {
        List<BigDecimal> balance = jdbcTemplate.query(
                """
                UPDATE account_balance_shards SET balance = balance + ?, updated_at = ?
                WHERE account_id = ? AND shard = ?
                RETURNING balance
                """,
                (rs, rowNum) -> rs.getBigDecimal("balance"),
                MoneyRounding.toStorageScale(signedAmount), java.sql.Timestamp.from(now), accountId, shard);
        if (balance.isEmpty()) {
            throw new IllegalStateException("No balance shard " + shard + " for system account " + accountId);
        }
        return balance.getFirst();
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse getJournalEntry(Caller caller, UUID id) {
        JournalEntry entry = journalEntries.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
        List<Posting> entryPostings = postings.findByJournalEntryIdOrderByIdAsc(id);
        Set<UUID> accountIds = entryPostings.stream().map(Posting::getAccountId).collect(Collectors.toSet());
        List<Account> involved = accounts.findAllById(accountIds);
        // A customer sees an entry that touches one of its organisation's accounts; anything else is a 404 (ADR 0018).
        if (involved.stream().noneMatch(account -> caller.mayAccess(account.getOwnerOrganizationId()))) {
            throw new JournalEntryNotFoundException(id);
        }
        Map<UUID, String> accountNumbers = involved.stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
        List<PostingResponse> responses = entryPostings.stream()
                .map(p -> PostingResponse.from(p, accountNumbers.get(p.getAccountId())))
                .toList();
        return JournalEntryResponse.from(entry, responses).forCaller(caller);
    }

    @Transactional(readOnly = true)
    public StatementPageResponse getStatement(Caller caller, String accountIdOrNumber, String cursor, int size) {
        if (size < 1 || size > 100) {
            throw new InvalidStatementCursorException("Statement page size must be between 1 and 100");
        }
        Account account = accountService.resolveVisible(caller, accountIdOrNumber);
        boolean showActor = caller.seesEverything();
        int fetchLimit = size + 1;
        List<StatementLineResponse> fetched;

        if (cursor == null || cursor.isBlank()) {
            fetched = jdbcTemplate.query(
                    """
                    SELECT p.id AS posting_id, p.journal_entry_id, je.entry_type, je.reference,
                           p.amount, p.currency, p.running_balance, p.created_at,
                           je.actor_subject, je.actor_client_id, je.actor_organization_id, je.request_id
                    FROM postings p
                    JOIN journal_entries je ON je.id = p.journal_entry_id
                    WHERE p.account_id = ?
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT ?
                    """,
                    (rs, row) -> mapStatementLine(rs, showActor),
                    account.getId(),
                    fetchLimit
            );
        } else {
            StatementCursor.Decoded decoded = StatementCursor.decode(cursor.trim(), account.getId());
            fetched = jdbcTemplate.query(
                    """
                    SELECT p.id AS posting_id, p.journal_entry_id, je.entry_type, je.reference,
                           p.amount, p.currency, p.running_balance, p.created_at,
                           je.actor_subject, je.actor_client_id, je.actor_organization_id, je.request_id
                    FROM postings p
                    JOIN journal_entries je ON je.id = p.journal_entry_id
                    WHERE p.account_id = ?
                      AND (p.created_at, p.id) < (?, ?)
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT ?
                    """,
                    (rs, row) -> mapStatementLine(rs, showActor),
                    account.getId(),
                    OffsetDateTime.ofInstant(decoded.createdAt(), ZoneOffset.UTC),
                    decoded.postingId(),
                    fetchLimit
            );
        }

        boolean hasMore = fetched.size() > size;
        List<StatementLineResponse> page = hasMore ? fetched.subList(0, size) : fetched;
        String nextCursor = null;
        if (hasMore) {
            StatementLineResponse last = page.getLast();
            nextCursor = StatementCursor.after(account.getId(), last.createdAt(), last.postingId()).encode();
        }
        return new StatementPageResponse(
                account.getId(),
                account.getAccountNumber(),
                account.getCurrency(),
                page,
                nextCursor
        );
    }

    /** Who made each entry is shown to operators and auditors only (ADR 0020). */
    private StatementLineResponse mapStatementLine(ResultSet rs, boolean showActor) throws SQLException {
        String currency = rs.getString("currency");
        Actor actor = !showActor || rs.getString("actor_client_id") == null ? null : new Actor(
                rs.getString("actor_subject"), rs.getString("actor_client_id"),
                rs.getObject("actor_organization_id", UUID.class), rs.getString("request_id"));
        return new StatementLineResponse(
                rs.getObject("posting_id", UUID.class),
                rs.getObject("journal_entry_id", UUID.class),
                JournalEntryType.valueOf(rs.getString("entry_type")),
                rs.getString("reference"),
                MoneyRounding.roundToMinorUnits(rs.getBigDecimal("amount"), currency),
                currency,
                rs.getBigDecimal("running_balance") == null
                        ? null
                        : MoneyRounding.roundToMinorUnits(rs.getBigDecimal("running_balance"), currency),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                actor
        );
    }
}
