package com.freightflow.banking.transfer;

import com.freightflow.banking.account.Account;
import com.freightflow.banking.account.AccountNotActiveException;
import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.account.InsufficientFundsException;
import com.freightflow.banking.common.LockTimeouts;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.common.UuidOrder;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.FxQuote;
import com.freightflow.banking.fx.FxQuoteService;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.journal.CurrencyMismatchException;
import com.freightflow.banking.journal.InvalidMonetaryAmountException;
import com.freightflow.banking.journal.JournalEntry;
import com.freightflow.banking.journal.JournalEntry.PostingInstruction;
import com.freightflow.banking.journal.JournalEntryRepository;
import com.freightflow.banking.journal.JournalEntryType;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.Posting;
import com.freightflow.banking.journal.PostingRepository;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.journal.dto.PostingResponse;
import com.freightflow.banking.transfer.dto.CreateTransferRequest;
import com.freightflow.banking.transfer.dto.TransferResponse;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransferService {

    private static final Logger log = LoggerFactory.getLogger(TransferService.class);

    public static final String STRATEGY_PESSIMISTIC = BankingProperties.STRATEGY_PESSIMISTIC;
    public static final String STRATEGY_CONDITIONAL_UPDATE = BankingProperties.STRATEGY_CONDITIONAL_UPDATE;

    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final JournalEntryRepository journalEntries;
    private final PostingRepository postings;
    private final TransferRepository transfers;
    private final FxQuoteService fxQuoteService;
    private final IdempotencyService idempotencyService;
    private final BankingProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final Clock clock;

    public TransferService(
            AccountService accountService,
            AccountRepository accounts,
            LedgerService ledgerService,
            JournalEntryRepository journalEntries,
            PostingRepository postings,
            TransferRepository transfers,
            FxQuoteService fxQuoteService,
            IdempotencyService idempotencyService,
            BankingProperties properties,
            JdbcTemplate jdbcTemplate,
            EntityManager entityManager,
            Clock clock) {
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.journalEntries = journalEntries;
        this.postings = postings;
        this.transfers = transfers;
        this.fxQuoteService = fxQuoteService;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.clock = clock;
        log.info("Transfer locking strategy: {}", properties.transferLockingStrategy());
    }

    @Transactional
    public TransferResponse createTransfer(CreateTransferRequest request, String idempotencyKey) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("sourceAccount", request.sourceAccount().trim().toUpperCase(Locale.ROOT));
        canonical.put("targetAccount", request.targetAccount().trim().toUpperCase(Locale.ROOT));
        canonical.put("amount", request.amount().stripTrailingZeros().toPlainString());
        canonical.put("currency", request.currency().trim().toUpperCase(Locale.ROOT));
        canonical.put("reference", request.reference().trim());
        canonical.put("fxQuoteId", request.fxQuoteId() != null ? request.fxQuoteId().toString() : "");

        return idempotencyService.execute(
                "transfer",
                idempotencyKey,
                canonical,
                201,
                TransferResponse.class,
                () -> executeTransfer(request)
        ).body();
    }

    protected TransferResponse executeTransfer(CreateTransferRequest request) {
        Currency requestedCurrency = MoneyRounding.validateSupportedCurrency(
                request.currency(), properties.normalizedCurrencies());
        Account source = accountService.resolveAccount(request.sourceAccount());
        Account target = accountService.resolveAccount(request.targetAccount());

        if (source.getId().equals(target.getId())) {
            throw new InvalidTransferException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "same-account-transfer",
                    "Invalid transfer accounts",
                    "sourceAccount and targetAccount must be distinct"
            );
        }
        if (request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new InvalidMonetaryAmountException("Transfer amount must be positive");
        }
        if (MoneyRounding.hasExcessMinorPrecision(request.amount(), requestedCurrency.getCurrencyCode())) {
            throw new InvalidMonetaryAmountException(
                    "Amount " + request.amount() + " has more than "
                            + MoneyRounding.minorUnits(requestedCurrency.getCurrencyCode()) + " decimal places");
        }

        if (!source.getCurrency().equals(target.getCurrency())) {
            return executeCrossCurrencyTransfer(source, target, request, requestedCurrency.getCurrencyCode());
        }

        if (request.fxQuoteId() != null) {
            throw new InvalidTransferException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "fx-quote-not-applicable",
                    "FX quote not applicable",
                    "fxQuoteId must not be provided when sourceAccount and targetAccount share currency "
                            + source.getCurrency()
            );
        }
        if (!source.getCurrency().equals(requestedCurrency.getCurrencyCode())) {
            throw new CurrencyMismatchException(
                    source.getCurrency(),
                    requestedCurrency.getCurrencyCode(),
                    "Transfer currency " + requestedCurrency.getCurrencyCode()
                            + " does not match account currency " + source.getCurrency()
            );
        }

        BigDecimal normalizedAmount = MoneyRounding.normalizeCustomerAmount(request.amount(), source.getCurrency());
        String reference = request.reference().trim();

        if (STRATEGY_CONDITIONAL_UPDATE.equals(properties.transferLockingStrategy())) {
            return executeConditionalSameCurrencyTransfer(source, target, normalizedAmount, reference);
        }

        JournalEntryResponse entry = ledgerService.recordEntry(
                JournalEntryType.TRANSFER,
                reference,
                null,
                null,
                null,
                List.of(
                        new PostingInstruction(source.getId(), normalizedAmount.negate(), source.getCurrency()),
                        new PostingInstruction(target.getId(), normalizedAmount, target.getCurrency())
                )
        );
        Transfer saved = transfers.save(Transfer.create(
                entry.id(),
                source.getId(),
                target.getId(),
                normalizedAmount,
                source.getCurrency(),
                normalizedAmount,
                target.getCurrency(),
                reference,
                null,
                null,
                entry.createdAt()
        ));
        transfers.flush();
        return TransferResponse.from(saved, source.getAccountNumber(), target.getAccountNumber(), entry.postings());
    }

    protected TransferResponse executeCrossCurrencyTransfer(
            Account source,
            Account target,
            CreateTransferRequest request,
            String requestedCurrency) {
        if (request.fxQuoteId() == null) {
            throw new InvalidTransferException(
                    HttpStatus.UNPROCESSABLE_CONTENT,
                    "fx-quote-required",
                    "FX quote required",
                    "Cross-currency transfers between " + source.getCurrency() + " and "
                            + target.getCurrency() + " require an fxQuoteId"
            );
        }

        FxQuote quote = fxQuoteService.consumeQuoteForTransfer(
                request.fxQuoteId(),
                source.getCurrency(),
                target.getCurrency(),
                request.amount(),
                requestedCurrency
        );

        Account fxPositionSell = accounts.findByAccountTypeAndCurrency(
                        AccountType.FX_POSITION, quote.getSellCurrency())
                .orElseThrow(() -> new IllegalStateException(
                        "System FX_POSITION account not configured for " + quote.getSellCurrency()));
        Account fxPositionBuy = accounts.findByAccountTypeAndCurrency(
                        AccountType.FX_POSITION, quote.getBuyCurrency())
                .orElseThrow(() -> new IllegalStateException(
                        "System FX_POSITION account not configured for " + quote.getBuyCurrency()));

        String reference = request.reference().trim();
        JournalEntryResponse entry = ledgerService.recordEntry(
                JournalEntryType.FX_TRANSFER,
                reference,
                null,
                quote.getId(),
                quote.getRate(),
                List.of(
                        new PostingInstruction(source.getId(), quote.getSellAmount().negate(), quote.getSellCurrency()),
                        new PostingInstruction(fxPositionSell.getId(), quote.getSellAmount(), quote.getSellCurrency()),
                        new PostingInstruction(fxPositionBuy.getId(), quote.getBuyAmount().negate(), quote.getBuyCurrency()),
                        new PostingInstruction(target.getId(), quote.getBuyAmount(), quote.getBuyCurrency())
                )
        );

        Transfer saved = transfers.save(Transfer.create(
                entry.id(),
                source.getId(),
                target.getId(),
                quote.getSellAmount(),
                quote.getSellCurrency(),
                quote.getBuyAmount(),
                quote.getBuyCurrency(),
                reference,
                quote.getId(),
                quote.getRate(),
                entry.createdAt()
        ));
        transfers.flush();
        return TransferResponse.from(saved, source.getAccountNumber(), target.getAccountNumber(), entry.postings());
    }

    /**
     * Alternative same-currency locking strategy benchmarked in ADR 0005: uses a conditional
     * {@code UPDATE accounts SET balance = balance - :x WHERE id = :id AND balance >= :x RETURNING balance}
     * on the debit side while preserving ascending PostgreSQL UUID lock ordering so bidirectional
     * {@code A -> B} and {@code B -> A} transfers never deadlock.
     */
    private TransferResponse executeConditionalSameCurrencyTransfer(
            Account source,
            Account target,
            BigDecimal amount,
            String reference) {
        Instant now = clock.instant();
        Timestamp ts = Timestamp.from(now);

        // The row locks (explicit for the target, implicit in each UPDATE) wait at most account-lock-timeout.
        BigDecimal[] running = LockTimeouts.withLockTimeout(jdbcTemplate, properties.ledger().accountLockTimeout(), () -> {
            // If target's UUID sorts before source's UUID in PostgreSQL, lock target first so lock order
            // is always ascending UUID order (preventing deadlocks on concurrent A->B and B->A).
            if (compareUuidUnsigned(target.getId(), source.getId()) < 0) {
                jdbcTemplate.queryForObject(
                        "SELECT id FROM accounts WHERE id = ? FOR NO KEY UPDATE",
                        UUID.class,
                        target.getId()
                );
            }

            List<BigDecimal> sourceNewBalances = jdbcTemplate.query(
                    """
                    UPDATE accounts
                    SET balance = balance - ?, updated_at = ?
                    WHERE id = ?
                      AND status = 'ACTIVE'
                      AND (account_type <> 'CUSTOMER' OR balance - held_amount >= ?)
                    RETURNING balance
                    """,
                    (rs, rowNum) -> rs.getBigDecimal("balance"),
                    amount,
                    ts,
                    source.getId(),
                    amount
            );

            if (sourceNewBalances.isEmpty()) {
                entityManager.refresh(source);
                source.assertActive();
                throw new InsufficientFundsException(
                        source.getAccountNumber(),
                        source.getCurrency(),
                        MoneyRounding.roundToMinorUnits(source.getBalance(), source.getCurrency()),
                        MoneyRounding.roundToMinorUnits(amount, source.getCurrency())
                );
            }

            List<BigDecimal> targetNewBalances = jdbcTemplate.query(
                    """
                    UPDATE accounts
                    SET balance = balance + ?, updated_at = ?
                    WHERE id = ?
                      AND status = 'ACTIVE'
                    RETURNING balance
                    """,
                    (rs, rowNum) -> rs.getBigDecimal("balance"),
                    amount,
                    ts,
                    target.getId()
            );

            if (targetNewBalances.isEmpty()) {
                entityManager.refresh(target);
                throw new AccountNotActiveException(target.getAccountNumber(), target.getStatus());
            }

            return new BigDecimal[] {sourceNewBalances.getFirst(), targetNewBalances.getFirst()};
        });
        BigDecimal sourceRunning = running[0];
        BigDecimal targetRunning = running[1];

        JournalEntry entry = journalEntries.save(
                JournalEntry.create(JournalEntryType.TRANSFER, reference, null, null, null, now));
        Posting debitPosting = postings.save(Posting.create(
                entry.getId(), source.getId(), amount.negate(), source.getCurrency(), sourceRunning, now));
        Posting creditPosting = postings.save(Posting.create(
                entry.getId(), target.getId(), amount, target.getCurrency(), targetRunning, now));
        Transfer saved = transfers.save(Transfer.create(
                entry.getId(),
                source.getId(),
                target.getId(),
                amount,
                source.getCurrency(),
                amount,
                target.getCurrency(),
                reference,
                null,
                null,
                now
        ));
        journalEntries.flush();
        postings.flush();
        transfers.flush();
        entityManager.clear();

        List<PostingResponse> postingResponses = List.of(
                PostingResponse.from(debitPosting, source.getAccountNumber()),
                PostingResponse.from(creditPosting, target.getAccountNumber())
        );
        return TransferResponse.from(saved, source.getAccountNumber(), target.getAccountNumber(), postingResponses);
    }

    @Transactional(readOnly = true)
    public TransferResponse getTransfer(UUID id) {
        Transfer transfer = transfers.findById(id)
                .orElseThrow(() -> new TransferNotFoundException(id));
        List<Posting> entryPostings = postings.findByJournalEntryIdOrderByIdAsc(transfer.getJournalEntryId());
        Set<UUID> accountIds = entryPostings.stream().map(Posting::getAccountId).collect(Collectors.toSet());
        accountIds.add(transfer.getSourceAccountId());
        accountIds.add(transfer.getTargetAccountId());

        Map<UUID, String> accountNumbers = accounts.findAllById(accountIds).stream()
                .collect(Collectors.toMap(Account::getId, Account::getAccountNumber));
        List<PostingResponse> postingResponses = entryPostings.stream()
                .map(p -> PostingResponse.from(p, accountNumbers.get(p.getAccountId())))
                .toList();
        return TransferResponse.from(
                transfer,
                accountNumbers.get(transfer.getSourceAccountId()),
                accountNumbers.get(transfer.getTargetAccountId()),
                postingResponses
        );
    }

    /** Compares two UUIDs in unsigned 128-bit big-endian byte order, matching PostgreSQL's {@code uuid} B-tree order. */
    public static int compareUuidUnsigned(UUID a, UUID b) {
        return UuidOrder.compare(a, b);
    }
}
