package com.freightflow.banking.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.freightflow.banking.telemetry.TraceContext;
import com.freightflow.banking.account.Account;
import com.freightflow.banking.approval.ApprovalRequest;
import com.freightflow.banking.common.MoneyRounding;
import com.freightflow.banking.hold.Hold;
import com.freightflow.banking.journal.JournalEntry;
import com.freightflow.banking.security.Actor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The events banking publishes (docs/events/, ADR 0023), written to the outbox in the caller's transaction
 * (ADR 0025). Payloads carry ids, amounts and states; no names, emails, account numbers or free text such as
 * references and comments typed by a person (ADR 0024). docs/events/*.schema.json is the contract, and
 * {@code EventContractIntegrationTest} checks every event written here against it.
 */
@Component
public class BankingEvents {

    /** Every type banking publishes, with its topic and the current schemaVersion of its schema. */
    public enum Type {
        ACCOUNT_OPENED("account.opened", EventTopics.ACCOUNT, 1),
        JOURNAL_ENTRY_POSTED("journal-entry.posted", EventTopics.JOURNAL_ENTRY, 1),
        HOLD_AUTHORISED("hold.authorised", EventTopics.HOLD, 1),
        HOLD_EXTENDED("hold.extended", EventTopics.HOLD, 1),
        HOLD_CAPTURED("hold.captured", EventTopics.HOLD, 1),
        HOLD_RELEASED("hold.released", EventTopics.HOLD, 1),
        HOLD_EXPIRED("hold.expired", EventTopics.HOLD, 1),
        APPROVAL_DECIDED("approval.decided", EventTopics.APPROVAL, 2);

        private final String name;
        private final String topic;
        private final int schemaVersion;

        Type(String name, String topic, int schemaVersion) {
            this.name = name;
            this.topic = topic;
            this.schemaVersion = schemaVersion;
        }

        public String typeName() {
            return name;
        }

        public String topic() {
            return topic;
        }

        public int schemaVersion() {
            return schemaVersion;
        }

        public static Type of(String name) {
            return Arrays.stream(values()).filter(type -> type.name.equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown event type " + name));
        }
    }

    // ------------------------------------------------------------------ payloads

    public record AccountOpened(UUID accountId, UUID organizationId, String accountType, String currency,
                                String status) {
    }

    public record JournalEntryPosted(UUID entryId, String entryType, UUID reversesEntryId, UUID approvalId,
                                     String fxRate, List<PostingLine> postings) {
    }

    /** @param organizationId the account's owner; null for a system account */
    public record PostingLine(UUID accountId, UUID organizationId, String amount, String currency) {
    }

    public record HoldView(UUID holdId, UUID payerAccountId, UUID organizationId, String reference, String status,
                           String amount, String currency, String payerAmount, String payerCurrency, String fxRate,
                           Instant expiresAt) {
    }

    public record HoldChanged(HoldView hold) {
    }

    public record HoldExtended(HoldView hold, Instant previousExpiresAt) {
    }

    public record HoldCaptured(HoldView hold, String capturedAmount, String capturedPayerAmount,
                               UUID journalEntryId) {
    }

    public record ApprovalDecided(UUID approvalId, String kind, String status, UUID accountId, String amount,
                                  String currency, UUID journalEntryId, UUID postedEntryId, UUID referenceRateId,
                                  RequestedBy requestedBy) {
    }

    public record RequestedBy(String subject, String clientId) {
    }

    // ------------------------------------------------------------------ publishing

    private final EventOutbox outbox;
    private final TraceContext traces;
    private final MeterRegistry meters;

    public BankingEvents(EventOutbox outbox, TraceContext traces, MeterRegistry meters) {
        this.outbox = outbox;
        this.traces = traces;
        this.meters = meters;
    }

    public void accountOpened(Account account, Actor actor) {
        append(Type.ACCOUNT_OPENED, account.getId(), new AccountOpened(account.getId(),
                account.getOwnerOrganizationId(), account.getAccountType().name(), account.getCurrency(),
                account.getStatus().name()), actor);
    }

    public void journalEntryPosted(JournalEntry entry, List<PostingLine> postings, Actor actor) {
        // The collector keeps every trace that moved money, whatever it samples of the rest (ADR 0031).
        traces.tag("freightflow.money.moved", true);
        append(Type.JOURNAL_ENTRY_POSTED, entry.getId(), new JournalEntryPosted(entry.getId(),
                entry.getEntryType().name(), entry.getReversesEntryId(), entry.getApprovalId(),
                rate(entry.getFxRate()), postings), actor);
    }

    public static PostingLine postingLine(UUID accountId, UUID organizationId, BigDecimal signedAmount,
                                          String currency) {
        return new PostingLine(accountId, organizationId, money(signedAmount), currency);
    }

    /** {@code hold.authorised}, {@code hold.released} or {@code hold.expired}: the hold as it now stands. */
    public void holdChanged(Type type, Hold hold, UUID organizationId, Actor actor) {
        if (type != Type.HOLD_AUTHORISED && type != Type.HOLD_RELEASED && type != Type.HOLD_EXPIRED) {
            throw new IllegalArgumentException(type + " carries more than the hold");
        }
        append(type, hold.getId(), new HoldChanged(view(hold, organizationId)), actor);
    }

    public void holdExtended(Hold hold, UUID organizationId, Instant previousExpiresAt, Actor actor) {
        append(Type.HOLD_EXTENDED, hold.getId(), new HoldExtended(view(hold, organizationId), previousExpiresAt),
                actor);
    }

    public void holdCaptured(Hold hold, UUID organizationId, Actor actor) {
        append(Type.HOLD_CAPTURED, hold.getId(), new HoldCaptured(view(hold, organizationId),
                money(hold.getCapturedAmount()), money(hold.getCapturedPayerAmount()),
                hold.getCaptureJournalEntryId()), actor);
    }

    public void approvalDecided(ApprovalRequest request, Actor actor) {
        append(Type.APPROVAL_DECIDED, request.getId(), new ApprovalDecided(request.getId(), request.getKind().name(),
                request.getStatus().name(), request.getAccountId(),
                request.getAmount() == null ? null : money(request.getAmount()), request.getCurrency(),
                request.getJournalEntryId(), request.getPostedEntryId(), request.getReferenceRateId(),
                new RequestedBy(request.getRequestedBy().subject(), request.getRequestedBy().clientId())), actor);
    }

    private void append(Type type, UUID aggregateId, Object payload, Actor actor) {
        outbox.append(type.topic(), type.typeName(), type.schemaVersion(), aggregateId, payload, actor);
        // Business metrics (ADR 0031), counted once the change has committed: a rollback counts nothing.
        if (type.topic().equals("freightflow.hold.v1")) {
            String event = type.typeName().substring("hold.".length());
            afterCommit(() -> meters.counter("freightflow.holds", "event", event).increment());
        }
        if (payload instanceof JournalEntryPosted posted) {
            countMoney(posted);
        }
        // The aggregate's id on the span that changed it, as freightflow.hold.id, freightflow.journal_entry.id, ...
        // (ADR 0030); ids only, never personal data (ADR 0024).
        traces.tag("freightflow." + aggregate(type) + ".id", aggregateId);
        traces.event("freightflow.event " + type.typeName());
    }

    /**
     * {@code freightflow.money.moved{currency, entry_type}}: the debit side of each posted entry, per currency, in
     * that currency's units. A gauge of money for dashboards, not the ledger: the ledger is the record (ADR 0004).
     */
    private void countMoney(JournalEntryPosted posted) {
        Map<String, BigDecimal> moved = new TreeMap<>();
        for (PostingLine line : posted.postings()) {
            BigDecimal amount = new BigDecimal(line.amount());
            if (amount.signum() > 0) {
                moved.merge(line.currency(), amount, BigDecimal::add);
            }
        }
        String entryType = posted.entryType().toLowerCase(Locale.ROOT);
        afterCommit(() -> moved.forEach((currency, amount) -> {
            meters.counter("freightflow.money.moved", "currency", currency, "entry_type", entryType)
                    .increment(amount.doubleValue());
            meters.counter("freightflow.journal.entries", "entry_type", entryType).increment();
        }));
    }

    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /** freightflow.journal-entry.v1 -> journal_entry. */
    static String aggregate(Type type) {
        return type.topic().split("\\.")[1].replace('-', '_');
    }

    private static HoldView view(Hold hold, UUID organizationId) {
        return new HoldView(hold.getId(), hold.getPayerAccountId(), organizationId, hold.getReference(),
                hold.getStatus().name(), money(hold.getAmount()), hold.getCurrency(), money(hold.getPayerAmount()),
                hold.getPayerCurrency(), rate(hold.getFxRate()), hold.getExpiresAt());
    }

    /** At the ledger's storage scale, the value the database holds, as a plain decimal string. */
    private static String money(BigDecimal amount) {
        return MoneyRounding.toStorageScale(amount).toPlainString();
    }

    private static String rate(BigDecimal rate) {
        return rate == null ? null : rate.stripTrailingZeros().toPlainString();
    }
}
