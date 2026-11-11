package com.freightflow.banking.hold;

import com.freightflow.banking.account.Account;
import com.freightflow.banking.account.AccountNotFoundException;
import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.AccountType;
import com.freightflow.banking.common.LockTimeouts;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.ExchangeRateProvider;
import com.freightflow.banking.fx.ExchangeRateProvider.RateQuote;
import com.freightflow.banking.hold.HoldExceptions.CaptureExceedsHoldException;
import com.freightflow.banking.hold.HoldExceptions.HoldExpiredException;
import com.freightflow.banking.hold.HoldExceptions.HoldNotActiveException;
import com.freightflow.banking.hold.HoldExceptions.HoldNotFoundException;
import com.freightflow.banking.hold.HoldExceptions.InvalidHoldException;
import com.freightflow.banking.hold.dto.CaptureHoldRequest;
import com.freightflow.banking.hold.dto.CreateHoldRequest;
import com.freightflow.banking.hold.dto.HoldResponse;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.idempotency.IdempotencyService.IdempotentResult;
import com.freightflow.banking.journal.InvalidMonetaryAmountException;
import com.freightflow.banking.journal.JournalEntry.PostingInstruction;
import com.freightflow.banking.journal.JournalEntryType;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Authorise, capture and release (ADR 0011).
 *
 * <p>Every change to a hold takes the payer's account row lock first and the hold row lock second, the same order
 * everywhere, so a capture, a release and the expiry sweeper racing on one hold serialise and exactly one of them
 * acts; the others find the hold no longer ACTIVE. The account lock is also what keeps {@code held_amount} and the
 * balance consistent with each other.
 */
@Service
public class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private final HoldRepository holds;
    private final AccountService accountService;
    private final AccountRepository accounts;
    private final LedgerService ledgerService;
    private final ExchangeRateProvider rateProvider;
    private final IdempotencyService idempotencyService;
    private final BankingProperties properties;
    private final HoldProperties holdProperties;
    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final Clock clock;

    public HoldService(HoldRepository holds, AccountService accountService, AccountRepository accounts,
                       LedgerService ledgerService, ExchangeRateProvider rateProvider,
                       IdempotencyService idempotencyService, BankingProperties properties,
                       HoldProperties holdProperties, JdbcTemplate jdbcTemplate, EntityManager entityManager,
                       Clock clock) {
        this.holds = holds;
        this.accountService = accountService;
        this.accounts = accounts;
        this.ledgerService = ledgerService;
        this.rateProvider = rateProvider;
        this.idempotencyService = idempotencyService;
        this.properties = properties;
        this.holdProperties = holdProperties;
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ authorise

    @Transactional
    public IdempotentResult<HoldResponse> createHold(CreateHoldRequest request, String idempotencyKey) {
        String currency = MoneyRounding.validateSupportedCurrency(request.currency(), properties.normalizedCurrencies())
                .getCurrencyCode();
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("payerAccount", request.payerAccount().trim().toUpperCase(java.util.Locale.ROOT));
        canonical.put("amount", request.amount().stripTrailingZeros().toPlainString());
        canonical.put("currency", currency);
        canonical.put("reference", request.reference().trim());
        canonical.put("expiresAt", request.expiresAt() == null ? "" : request.expiresAt().toString());
        return idempotencyService.execute("hold", idempotencyKey, canonical, 201, HoldResponse.class,
                () -> authorise(request, currency));
    }

    private HoldResponse authorise(CreateHoldRequest request, String currency) {
        assertMinorUnits(request.amount(), currency);
        BigDecimal amount = MoneyRounding.normalizeCustomerAmount(request.amount(), currency);
        Instant now = clock.instant();
        Instant expiresAt = expiryFor(request.expiresAt(), now);

        Account payer = accountService.resolveAccount(request.payerAccount());
        if (payer.getAccountType() != AccountType.CUSTOMER) {
            throw new InvalidHoldException("Holds can only be placed on customer accounts, not " + payer.getAccountType());
        }
        BigDecimal payerAmount = amount;
        Hold.FxLock fx = null;
        if (!payer.getCurrency().equals(currency)) {
            // Banking owns FX: the payer is charged at banking's rate, fixed now and used again by the capture.
            RateQuote rate = rateProvider.lookupRate(payer.getCurrency(), currency);
            payerAmount = MoneyRounding.normalizeCustomerAmount(
                    amount.divide(rate.effectiveRate(), MoneyRounding.STORAGE_SCALE + 4, MoneyRounding.ROUNDING_MODE),
                    payer.getCurrency());
            fx = new Hold.FxLock(rate.effectiveRate(), rate.midRate(), rate.spread());
        }

        Account locked = lockAccount(payer.getId());
        locked.reserve(payerAmount, now);
        Hold hold = holds.saveAndFlush(Hold.create(locked.getId(), request.reference(), amount, currency,
                payerAmount, locked.getCurrency(), fx, expiresAt, now));
        log.info("Hold {} authorised: {} {} on {} ({} {})", hold.getId(), amount, currency,
                locked.getAccountNumber(), payerAmount, locked.getCurrency());
        return HoldResponse.from(hold, locked.getAccountNumber());
    }

    // ------------------------------------------------------------------ capture

    @Transactional
    public HoldResponse capture(UUID holdId, CaptureHoldRequest request, String idempotencyKey) {
        BigDecimal requested = request == null ? null : request.amount();
        Map<String, Object> canonical = Map.of("holdId", holdId.toString(),
                "amount", requested == null ? "full" : requested.stripTrailingZeros().toPlainString());
        return idempotencyService.execute("hold-capture:" + holdId, idempotencyKey, canonical, 200,
                HoldResponse.class, () -> doCapture(holdId, requested)).body();
    }

    private HoldResponse doCapture(UUID holdId, BigDecimal requested) {
        Instant now = clock.instant();
        Locked locked = lockPayerThenHold(holdId);
        Hold hold = locked.hold();
        assertActive(hold, now);

        BigDecimal captured = hold.getAmount();
        if (requested != null) {
            assertMinorUnits(requested, hold.getCurrency());
            captured = MoneyRounding.normalizeCustomerAmount(requested, hold.getCurrency());
            if (captured.compareTo(hold.getAmount()) > 0) {
                throw new CaptureExceedsHoldException(holdId,
                        MoneyRounding.roundToMinorUnits(hold.getAmount(), hold.getCurrency()),
                        MoneyRounding.roundToMinorUnits(captured, hold.getCurrency()));
            }
        }
        BigDecimal payerDebit = !hold.isCrossCurrency()
                ? captured
                : MoneyRounding.normalizeCustomerAmount(
                        captured.divide(hold.getFxRate(), MoneyRounding.STORAGE_SCALE + 4, MoneyRounding.ROUNDING_MODE),
                        hold.getPayerCurrency())
                        .min(hold.getPayerAmount());

        // The whole reservation is freed and the captured part debited in one transaction, under the account lock.
        locked.account().releaseReservation(hold.getPayerAmount(), now);
        Account revenue = systemAccount(AccountType.FREIGHT_REVENUE, hold.getCurrency());
        List<PostingInstruction> postings;
        if (!hold.isCrossCurrency()) {
            postings = List.of(
                    new PostingInstruction(hold.getPayerAccountId(), captured.negate(), hold.getCurrency()),
                    new PostingInstruction(revenue.getId(), captured, hold.getCurrency()));
        } else {
            Account fxPayer = systemAccount(AccountType.FX_POSITION, hold.getPayerCurrency());
            Account fxCharge = systemAccount(AccountType.FX_POSITION, hold.getCurrency());
            postings = List.of(
                    new PostingInstruction(hold.getPayerAccountId(), payerDebit.negate(), hold.getPayerCurrency()),
                    new PostingInstruction(fxPayer.getId(), payerDebit, hold.getPayerCurrency()),
                    new PostingInstruction(fxCharge.getId(), captured.negate(), hold.getCurrency()),
                    new PostingInstruction(revenue.getId(), captured, hold.getCurrency()));
        }
        JournalEntryResponse entry = ledgerService.recordEntry(JournalEntryType.CAPTURE,
                "CAPTURE " + hold.getReference(), null, null, hold.getFxRate(), postings);

        int updated = jdbcTemplate.update("""
                UPDATE holds SET status = 'CAPTURED', captured_amount = ?, captured_payer_amount = ?,
                                 capture_journal_entry_id = ?, closed_at = ?
                WHERE id = ? AND status = 'ACTIVE'
                """, captured, payerDebit, entry.id(), Timestamp.from(now), holdId);
        assertOneRow(updated, holdId);
        log.info("Hold {} captured: {} {} (journal entry {})", holdId, captured, hold.getCurrency(), entry.id());
        return reload(holdId);
    }

    // ------------------------------------------------------------------ release and expiry

    @Transactional
    public HoldResponse release(UUID holdId, String idempotencyKey) {
        return idempotencyService.execute("hold-release:" + holdId, idempotencyKey, Map.of("holdId", holdId.toString()),
                200, HoldResponse.class, () -> close(holdId, HoldStatus.RELEASED)).body();
    }

    /**
     * Expires one hold if it is still ACTIVE and past its expiry. Safe to run on every instance at once: the second
     * caller waits for the locks and then finds the hold closed.
     *
     * @return true if this call expired the hold
     */
    @Transactional
    public boolean expire(UUID holdId) {
        Instant now = clock.instant();
        Locked locked = lockPayerThenHold(holdId);
        Hold hold = locked.hold();
        if (hold.getStatus() != HoldStatus.ACTIVE || now.isBefore(hold.getExpiresAt())) {
            return false;
        }
        locked.account().releaseReservation(hold.getPayerAmount(), now);
        entityManager.flush();
        assertOneRow(jdbcTemplate.update(
                "UPDATE holds SET status = 'EXPIRED', closed_at = ? WHERE id = ? AND status = 'ACTIVE'",
                Timestamp.from(now), holdId), holdId);
        log.info("Hold {} expired; {} {} released", holdId, hold.getPayerAmount(), hold.getPayerCurrency());
        return true;
    }

    private HoldResponse close(UUID holdId, HoldStatus status) {
        Instant now = clock.instant();
        Locked locked = lockPayerThenHold(holdId);
        Hold hold = locked.hold();
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException(holdId, hold.getStatus());
        }
        locked.account().releaseReservation(hold.getPayerAmount(), now);
        entityManager.flush();
        assertOneRow(jdbcTemplate.update(
                "UPDATE holds SET status = ?, closed_at = ? WHERE id = ? AND status = 'ACTIVE'",
                status.name(), Timestamp.from(now), holdId), holdId);
        log.info("Hold {} {}: {} {} freed", holdId, status, hold.getPayerAmount(), hold.getPayerCurrency());
        return reload(holdId);
    }

    @Transactional(readOnly = true)
    public HoldResponse getHold(UUID holdId) {
        Hold hold = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return HoldResponse.from(hold, accountNumber(hold.getPayerAccountId()));
    }

    @Transactional(readOnly = true)
    public List<HoldResponse> findByReference(String reference) {
        return holds.findTop50ByReferenceOrderByCreatedAtDesc(reference.trim()).stream()
                .map(hold -> HoldResponse.from(hold, accountNumber(hold.getPayerAccountId())))
                .toList();
    }

    // ------------------------------------------------------------------ helpers

    private record Locked(Account account, Hold hold) {
    }

    /** Account row first, hold row second: the one lock order for every hold change. */
    private Locked lockPayerThenHold(UUID holdId) {
        Hold unlocked = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        Account account = lockAccount(unlocked.getPayerAccountId());
        LockTimeouts.withLockTimeout(jdbcTemplate, properties.ledger().accountLockTimeout(),
                () -> jdbcTemplate.queryForObject("SELECT id FROM holds WHERE id = ? FOR UPDATE", UUID.class, holdId));
        Hold fresh = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return new Locked(account, fresh);
    }

    private Account lockAccount(UUID accountId) {
        entityManager.flush();
        entityManager.clear();
        List<Account> locked = LockTimeouts.withLockTimeout(jdbcTemplate, properties.ledger().accountLockTimeout(),
                () -> accounts.findAllByIdInForUpdate(Set.of(accountId)));
        if (locked.isEmpty()) {
            throw new AccountNotFoundException(accountId);
        }
        return locked.getFirst();
    }

    private void assertActive(Hold hold, Instant now) {
        if (hold.getStatus() != HoldStatus.ACTIVE) {
            throw new HoldNotActiveException(hold.getId(), hold.getStatus());
        }
        if (!now.isBefore(hold.getExpiresAt())) {
            throw new HoldExpiredException(hold.getId(), hold.getExpiresAt());
        }
    }

    private Instant expiryFor(Instant requested, Instant now) {
        if (requested == null) {
            return now.plus(holdProperties.defaultTtl());
        }
        if (!requested.isAfter(now) || requested.isAfter(now.plus(holdProperties.maxTtl()))) {
            throw new InvalidHoldException("expiresAt must be in the future and at most "
                    + holdProperties.maxTtl().toDays() + " days away");
        }
        return requested;
    }

    private static void assertMinorUnits(BigDecimal amount, String currency) {
        if (amount.signum() <= 0) {
            throw new InvalidMonetaryAmountException("Amount must be positive");
        }
        if (MoneyRounding.hasExcessMinorPrecision(amount, currency)) {
            throw new InvalidMonetaryAmountException("Amount " + amount + " has more than "
                    + MoneyRounding.minorUnits(currency) + " decimal places");
        }
    }

    private Account systemAccount(AccountType type, String currency) {
        return accounts.findByAccountTypeAndCurrency(type, currency)
                .orElseThrow(() -> new IllegalStateException("System account " + type + " not configured for " + currency));
    }

    private static void assertOneRow(int updated, UUID holdId) {
        if (updated != 1) {
            // Cannot happen while we hold the row lock; if it does, roll back rather than leave a half-closed hold.
            throw new IllegalStateException("Hold " + holdId + " changed under its row lock");
        }
    }

    private HoldResponse reload(UUID holdId) {
        entityManager.flush();
        entityManager.clear();
        Hold hold = holds.findById(holdId).orElseThrow(() -> new HoldNotFoundException(holdId));
        return HoldResponse.from(hold, accountNumber(hold.getPayerAccountId()));
    }

    private String accountNumber(UUID accountId) {
        return accounts.findById(accountId).map(Account::getAccountNumber)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
