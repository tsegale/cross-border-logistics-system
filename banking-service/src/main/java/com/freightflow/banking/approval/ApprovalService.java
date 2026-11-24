package com.freightflow.banking.approval;

import com.freightflow.banking.account.Account;
import com.freightflow.banking.account.AccountRepository;
import com.freightflow.banking.approval.ApprovalExceptions.ApprovalExpiredException;
import com.freightflow.banking.approval.ApprovalExceptions.ApprovalNotFoundException;
import com.freightflow.banking.approval.ApprovalExceptions.FourEyesViolationException;
import com.freightflow.banking.approval.ApprovalExceptions.InvalidApprovalRequestException;
import com.freightflow.banking.approval.ApprovalExceptions.ReversalAlreadyRequestedException;
import com.freightflow.banking.approval.dto.ApprovalListResponse;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.common.LockTimeouts;
import com.freightflow.banking.config.BankingProperties;
import com.freightflow.banking.fx.rates.ReferenceRateExceptions.RateConfirmationAlreadyRequestedException;
import com.freightflow.banking.fx.rates.ReferenceRateService;
import com.freightflow.banking.events.BankingEvents;
import com.freightflow.banking.idempotency.IdempotencyService;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.LedgerService.ValidatedDeposit;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import com.freightflow.banking.security.Actor;
import com.freightflow.banking.security.Caller;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The four-eyes principle for operator money operations (ADR 0021). One operator requests a deposit or a reversal;
 * a different operator approves it, and only the approval posts the journal entry, with the checker as its actor and
 * the request, naming the maker, as its approval. Requests expire unanswered.
 *
 * <p>A decision locks the request row first, so two checkers deciding at once run one after the other: the second
 * finds it decided. {@code journal_entries.approval_id} is unique as well, so even a bug here cannot post twice.
 */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final int MAX_PAGE = 100;

    private final ApprovalRepository approvals;
    private final AccountRepository accounts;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final ApprovalProperties properties;
    private final BankingProperties banking;
    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meters;
    private final BankingEvents events;
    private final ReferenceRateService referenceRates;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, AccountRepository accounts, LedgerService ledger,
                           IdempotencyService idempotency, ApprovalProperties properties, BankingProperties banking,
                           JdbcTemplate jdbcTemplate, MeterRegistry meters, BankingEvents events,
                           ReferenceRateService referenceRates, Clock clock) {
        this.events = events;
        this.referenceRates = referenceRates;
        this.approvals = approvals;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.properties = properties;
        this.banking = banking;
        this.jdbcTemplate = jdbcTemplate;
        this.meters = meters;
        this.clock = clock;
    }

    @Transactional
    public ApprovalResponse request(Caller caller, CreateApprovalRequest body, String idempotencyKey) {
        Map<String, Object> canonical = canonical(body);
        return idempotency.execute("approval", caller, idempotencyKey, canonical, 201, ApprovalResponse.class,
                () -> create(caller.actor(), body)).body();
    }

    private ApprovalResponse create(Actor maker, CreateApprovalRequest body) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(properties.ttl());
        ApprovalRequest request = switch (body.kind()) {
            case DEPOSIT -> {
                ValidatedDeposit deposit = ledger.validateDeposit(body.account(), body.amount(), body.currency(),
                        body.reference());
                yield ApprovalRequest.deposit(deposit.account().getId(), deposit.amount(), deposit.currency(),
                        deposit.reference(), maker, now, expiresAt);
            }
            case REVERSAL -> {
                ledger.validateReversal(body.journalEntryId());
                if (pendingReversalExists(body.journalEntryId())) {
                    throw new ReversalAlreadyRequestedException(body.journalEntryId());
                }
                yield ApprovalRequest.reversal(body.journalEntryId(), reasonOf(body), maker, now, expiresAt);
            }
            case RATE_CONFIRMATION -> {
                referenceRates.requireQuarantined(body.referenceRateId());
                if (pendingConfirmationExists(body.referenceRateId())) {
                    throw new RateConfirmationAlreadyRequestedException(body.referenceRateId());
                }
                yield ApprovalRequest.rateConfirmation(body.referenceRateId(), trimmed(body.reason()), maker, now,
                        expiresAt);
            }
        };
        ApprovalRequest saved = approvals.saveAndFlush(request);
        count("requested", saved.getKind());
        log.info("Approval {} requested: {} by {}", saved.getId(), saved.getKind(), maker.subject());
        return toResponse(saved);
    }

    /** Approves and posts. Moves money, so it takes an Idempotency-Key like any other money movement. */
    @Transactional
    public ApprovalResponse approve(Caller caller, UUID id, String comment, String idempotencyKey) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("approvalId", id.toString());
        canonical.put("decision", "APPROVE");
        canonical.put("comment", comment == null ? "" : comment.trim());
        return idempotency.execute("approval-decision:" + id, caller, idempotencyKey, canonical, 200,
                ApprovalResponse.class, () -> decideApproval(caller.actor(), id, trimmed(comment))).body();
    }

    private ApprovalResponse decideApproval(Actor checker, UUID id, String comment) {
        Instant now = clock.instant();
        ApprovalRequest request = lockPending(id, checker, now);
        if (request.getKind() == ApprovalKind.RATE_CONFIRMATION) {
            referenceRates.decide(request.getReferenceRateId(), true, id);
            request.approveWithoutEntry(checker, now, comment);
            approvals.saveAndFlush(request);
            events.approvalDecided(request, checker);
            count("approved", request.getKind());
            log.info("Approval {} approved by {}: reference rate {} confirmed", id, checker.subject(),
                    request.getReferenceRateId());
            return toResponse(request);
        }
        JournalEntryResponse entry = switch (request.getKind()) {
            case DEPOSIT -> ledger.postDeposit(checker, id, request.getAccountId(), request.getAmount(),
                    request.getCurrency(), request.getReference());
            case REVERSAL -> ledger.postReversal(checker, id, request.getJournalEntryId(), request.getReason());
            case RATE_CONFIRMATION -> throw new IllegalStateException("a rate confirmation posts no entry");
        };
        // Posting clears the persistence context; the row is still locked by this transaction.
        ApprovalRequest decided = approvals.findById(id).orElseThrow(() -> new ApprovalNotFoundException(id));
        decided.approve(checker, now, comment, entry.id());
        approvals.saveAndFlush(decided);
        events.approvalDecided(decided, checker);
        count("approved", decided.getKind());
        log.info("Approval {} approved by {}: entry {}", id, checker.subject(), entry.id());
        return toResponse(decided);
    }

    /** Rejects. Moves no money, so no Idempotency-Key: a second rejection is a 409, the request already decided. */
    @Transactional
    public ApprovalResponse reject(Caller caller, UUID id, String comment) {
        String reason = trimmed(comment);
        if (reason == null) {
            throw new InvalidApprovalRequestException("A rejection needs a comment saying why");
        }
        Actor checker = caller.actor();
        Instant now = clock.instant();
        ApprovalRequest request = lockPending(id, checker, now);
        if (request.getKind() == ApprovalKind.RATE_CONFIRMATION) {
            // Two operators agree the rate is wrong: it is never used. An expired request leaves it quarantined.
            referenceRates.decide(request.getReferenceRateId(), false, id);
        }
        request.reject(checker, now, reason);
        approvals.saveAndFlush(request);
        events.approvalDecided(request, checker);
        count("rejected", request.getKind());
        log.info("Approval {} rejected by {}", id, checker.subject());
        return toResponse(request);
    }

    /** Marks one request past its expiry as EXPIRED; false if it was decided or expired meanwhile. */
    @Transactional
    public boolean expire(UUID id) {
        Instant now = clock.instant();
        ApprovalRequest request = lock(id);
        if (request.getStatus() != ApprovalStatus.PENDING || !request.isExpiredAt(now)) {
            return false;
        }
        request.expire(now);
        approvals.saveAndFlush(request);
        events.approvalDecided(request, null);
        count("expired", request.getKind());
        return true;
    }

    @Transactional(readOnly = true)
    public ApprovalResponse get(UUID id) {
        return toResponse(approvals.findById(id).orElseThrow(() -> new ApprovalNotFoundException(id)));
    }

    @Transactional(readOnly = true)
    public ApprovalListResponse list(ApprovalStatus status, int limit) {
        if (limit < 1 || limit > MAX_PAGE) {
            throw new InvalidApprovalRequestException("limit must be between 1 and " + MAX_PAGE);
        }
        List<ApprovalResponse> items = approvals.findOldestFirst(status.name(), limit).stream()
                .map(this::toResponse).toList();
        return new ApprovalListResponse(items);
    }

    /**
     * Locks the request and checks, in this order, that it is still pending, that the checker is not the maker,
     * and that it has not expired.
     */
    private ApprovalRequest lockPending(UUID id, Actor checker, Instant now) {
        ApprovalRequest request = lock(id);
        request.requirePending();
        if (request.wasRequestedBy(checker)) {
            throw new FourEyesViolationException(id);
        }
        if (request.isExpiredAt(now)) {
            throw new ApprovalExpiredException(id, request.getExpiresAt());
        }
        return request;
    }

    private ApprovalRequest lock(UUID id) {
        return LockTimeouts.withLockTimeout(jdbcTemplate, banking.ledger().accountLockTimeout(),
                () -> approvals.findByIdForUpdate(id)).orElseThrow(() -> new ApprovalNotFoundException(id));
    }

    private boolean pendingConfirmationExists(UUID referenceRateId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM approval_requests
                               WHERE kind = 'RATE_CONFIRMATION' AND status = 'PENDING' AND reference_rate_id = ?)
                """, Boolean.class, referenceRateId);
        return Boolean.TRUE.equals(exists);
    }

    private boolean pendingReversalExists(UUID journalEntryId) {
        Boolean exists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM approval_requests
                               WHERE kind = 'REVERSAL' AND status = 'PENDING' AND journal_entry_id = ?)
                """, Boolean.class, journalEntryId);
        return Boolean.TRUE.equals(exists);
    }

    /** What the Idempotency-Key is checked against: the fields of the kind, normalised; others must be absent. */
    private static Map<String, Object> canonical(CreateApprovalRequest body) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("kind", body.kind().name());
        switch (body.kind()) {
            case DEPOSIT -> {
                if (body.account() == null || body.account().isBlank() || body.amount() == null
                        || body.currency() == null || body.reference() == null || body.reference().isBlank()) {
                    throw new InvalidApprovalRequestException("A DEPOSIT needs account, amount, currency and reference");
                }
                if (body.journalEntryId() != null || body.reason() != null || body.referenceRateId() != null) {
                    throw new InvalidApprovalRequestException(
                            "A DEPOSIT takes no journalEntryId, reason or referenceRateId");
                }
                canonical.put("account", body.account().trim().toUpperCase(Locale.ROOT));
                canonical.put("amount", body.amount().stripTrailingZeros().toPlainString());
                canonical.put("currency", body.currency().trim().toUpperCase(Locale.ROOT));
                canonical.put("reference", body.reference().trim());
            }
            case REVERSAL -> {
                if (body.journalEntryId() == null) {
                    throw new InvalidApprovalRequestException("A REVERSAL needs journalEntryId");
                }
                if (body.account() != null || body.amount() != null || body.currency() != null || body.reference() != null
                        || body.referenceRateId() != null) {
                    throw new InvalidApprovalRequestException(
                            "A REVERSAL takes no account, amount, currency, reference or referenceRateId");
                }
                canonical.put("journalEntryId", body.journalEntryId().toString());
                canonical.put("reason", reasonOf(body));
            }
            case RATE_CONFIRMATION -> {
                if (body.referenceRateId() == null || trimmed(body.reason()) == null) {
                    throw new InvalidApprovalRequestException(
                            "A RATE_CONFIRMATION needs referenceRateId and a reason saying why the rate is right");
                }
                if (body.account() != null || body.amount() != null || body.currency() != null || body.reference() != null
                        || body.journalEntryId() != null) {
                    throw new InvalidApprovalRequestException(
                            "A RATE_CONFIRMATION takes no account, amount, currency, reference or journalEntryId");
                }
                canonical.put("referenceRateId", body.referenceRateId().toString());
                canonical.put("reason", trimmed(body.reason()));
            }
        }
        return canonical;
    }

    private static String reasonOf(CreateApprovalRequest body) {
        String reason = trimmed(body.reason());
        return reason != null ? reason : "Reversal of " + body.journalEntryId();
    }

    private static String trimmed(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    private ApprovalResponse toResponse(ApprovalRequest request) {
        String accountNumber = request.getAccountId() == null ? null
                : accounts.findById(request.getAccountId()).map(Account::getAccountNumber).orElse(null);
        return ApprovalResponse.from(request, accountNumber);
    }

    private void count(String outcome, ApprovalKind kind) {
        meters.counter("freightflow.approvals", "outcome", outcome, "kind", kind.name().toLowerCase(Locale.ROOT))
                .increment();
    }
}
