package com.freightflow.banking.journal;

import com.freightflow.banking.account.Account;
import com.freightflow.banking.account.AccountNotFoundException;
import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.journal.JournalEntry.PostingInstruction;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.journal.dto.PostingResponse;
import com.freightflow.banking.journal.dto.ReversalRequest;
import com.freightflow.banking.journal.dto.StatementLineResponse;
import com.freightflow.banking.journal.dto.StatementPageResponse;
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
    private final Clock clock;

    public LedgerService(
            AccountService accountService,
            AccountRepository accounts,
            JournalEntryRepository journalEntries,
            PostingRepository postings,
            IdempotencyService idempotencyService,
            BankingProperties properties,
            JdbcTemplate jdbcTemplate,
            Clock clock) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.journalEntries = journalEntries;
        this.postings = postings;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Transactional
    public JournalEntryResponse deposit(String accountIdOrNumber, DepositRequest request, String idempotencyKey) {
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
                idempotencyKey,
                canonical,
                201,
                JournalEntryResponse.class,
                () -> recordEntry(
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
    public JournalEntryResponse reverse(UUID journalEntryId, ReversalRequest request, String idempotencyKey) {
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
                idempotencyKey,
                canonical,
                201,
                JournalEntryResponse.class,
                () -> executeReversal(journalEntryId, reason)
        ).body();
    }

    private JournalEntryResponse executeReversal(UUID journalEntryId, String reason) {
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
     * Locks every involved account row with {@code SELECT ... FOR UPDATE} in ascending {@code id}
     * order, validates double-entry balance per currency, updates account balances and appends the
     * journal entry and its postings in one transaction.
     */
    @Transactional
    public JournalEntryResponse recordEntry(
            JournalEntryType entryType,
            String reference,
            UUID reversesEntryId,
            UUID fxQuoteId,
            BigDecimal fxRate,
            List<PostingInstruction> instructions) {
        JournalEntry.assertBalanced(instructions);

        Set<UUID> accountIds = instructions.stream()
                .map(PostingInstruction::accountId)
                .collect(Collectors.toSet());
        List<Account> lockedAccounts = accounts.findAllByIdInForUpdate(accountIds);
        if (lockedAccounts.size() != accountIds.size()) {
            throw new AccountNotFoundException("One or more accounts in journal entry were not found");
        }
        Map<UUID, Account> accountsById = lockedAccounts.stream()
                .collect(Collectors.toMap(Account::getId, Function.identity()));

        Instant now = clock.instant();
        JournalEntry entry = journalEntries.save(
                JournalEntry.create(entryType, reference, reversesEntryId, fxQuoteId, fxRate, now));

        List<PostingResponse> postingResponses = new ArrayList<>(instructions.size());
        for (PostingInstruction instruction : instructions) {
            Account account = accountsById.get(instruction.accountId());
            if (!account.getCurrency().equalsIgnoreCase(instruction.currency())) {
                throw new CurrencyMismatchException(
                        account.getCurrency(),
                        instruction.currency(),
                        "Posting currency " + instruction.currency()
                                + " does not match account currency " + account.getCurrency());
            }
            BigDecimal runningBalance = account.applyPosting(instruction.signedAmount(), now);
            Posting savedPosting = postings.save(Posting.create(
                    entry.getId(),
                    account.getId(),
                    instruction.signedAmount(),
                    account.getCurrency(),
                    runningBalance,
                    now
            ));
            postingResponses.add(PostingResponse.from(savedPosting, account.getAccountNumber()));
        }
        journalEntries.flush();
        postings.flush();
        return JournalEntryResponse.from(entry, postingResponses);
    }

    @Transactional(readOnly = true)
    public JournalEntryResponse getJournalEntry(UUID id) {
        JournalEntry entry = journalEntries.findById(id)
                .orElseThrow(() -> new JournalEntryNotFoundException(id));
        List<Posting> entryPostings = postings.findByJournalEntryIdOrderByIdAsc(id);
        Set<UUID> accountIds = entryPostings.stream().map(Posting::getAccountId).collect(Collectors.toSet());
        Map<UUID, String> accountNumbers = accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
        List<PostingResponse> responses = entryPostings.stream()
                .map(p -> PostingResponse.from(p, accountNumbers.get(p.getAccountId())))
                .toList();
        return JournalEntryResponse.from(entry, responses);
    }

    @Transactional(readOnly = true)
    public StatementPageResponse getStatement(String accountIdOrNumber, String cursor, int size) {
        if (size < 1 || size > 100) {
            throw new InvalidStatementCursorException("Statement page size must be between 1 and 100");
        }
        Account account = accountService.resolveAccount(accountIdOrNumber);
        int fetchLimit = size + 1;
        List<StatementLineResponse> fetched;

        if (cursor == null || cursor.isBlank()) {
            fetched = jdbcTemplate.query(
                    """
                    SELECT p.id AS posting_id, p.journal_entry_id, je.entry_type, je.reference,
                           p.amount, p.currency, p.running_balance, p.created_at
                    FROM postings p
                    JOIN journal_entries je ON je.id = p.journal_entry_id
                    WHERE p.account_id = ?
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT ?
                    """,
                    this::mapStatementLine,
                    account.getId(),
                    fetchLimit
            );
        } else {
            StatementCursor.Decoded decoded = StatementCursor.decode(cursor.trim(), account.getId());
            fetched = jdbcTemplate.query(
                    """
                    SELECT p.id AS posting_id, p.journal_entry_id, je.entry_type, je.reference,
                           p.amount, p.currency, p.running_balance, p.created_at
                    FROM postings p
                    JOIN journal_entries je ON je.id = p.journal_entry_id
                    WHERE p.account_id = ?
                      AND (p.created_at, p.id) < (?, ?)
                    ORDER BY p.created_at DESC, p.id DESC
                    LIMIT ?
                    """,
                    this::mapStatementLine,
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

    private StatementLineResponse mapStatementLine(ResultSet rs, int rowNum) throws SQLException {
        String currency = rs.getString("currency");
        return new StatementLineResponse(
                rs.getObject("posting_id", UUID.class),
                rs.getObject("journal_entry_id", UUID.class),
                JournalEntryType.valueOf(rs.getString("entry_type")),
                rs.getString("reference"),
                MoneyRounding.roundToMinorUnits(rs.getBigDecimal("amount"), currency),
                currency,
                MoneyRounding.roundToMinorUnits(rs.getBigDecimal("running_balance"), currency),
                rs.getObject("created_at", OffsetDateTime.class).toInstant()
        );
    }
}
