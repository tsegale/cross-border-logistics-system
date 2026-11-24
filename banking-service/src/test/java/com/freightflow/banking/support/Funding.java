package com.freightflow.banking.support;

import com.freightflow.banking.approval.ApprovalKind;
import com.freightflow.banking.approval.ApprovalService;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.JournalEntryResponse;
import org.springframework.boot.test.context.TestComponent;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Money into and corrections of test accounts, the only way production allows (ADR 0021): {@link TestCallers#OPERATOR}
 * requests, {@link TestCallers#SECOND_OPERATOR} approves. Tests that are about the approval flow itself call
 * {@link ApprovalService} or the HTTP API directly.
 */
@TestComponent
public class Funding {

    private final ApprovalService approvals;
    private final LedgerService ledger;

    public Funding(ApprovalService approvals, LedgerService ledger) {
        this.approvals = approvals;
        this.ledger = ledger;
    }

    /** Deposits {@code amount} into the account (id or number) and returns the posted entry. */
    public JournalEntryResponse deposit(String account, BigDecimal amount, String currency, String reference) {
        ApprovalResponse requested = approvals.request(TestCallers.OPERATOR,
                new CreateApprovalRequest(ApprovalKind.DEPOSIT, account, amount, currency, reference, null, null, null), key());
        return approve(requested);
    }

    public JournalEntryResponse deposit(String account, String amount, String currency) {
        return deposit(account, new BigDecimal(amount), currency, "SEED");
    }

    /** Reverses the entry and returns the reversal. */
    public JournalEntryResponse reverse(UUID journalEntryId, String reason) {
        ApprovalResponse requested = approvals.request(TestCallers.OPERATOR,
                new CreateApprovalRequest(ApprovalKind.REVERSAL, null, null, null, null, journalEntryId, reason, null), key());
        return approve(requested);
    }

    private JournalEntryResponse approve(ApprovalResponse requested) {
        ApprovalResponse approved = approvals.approve(TestCallers.SECOND_OPERATOR, requested.id(), null, key());
        return ledger.getJournalEntry(TestCallers.OPERATOR, approved.postedEntryId());
    }

    private static String key() {
        return "funding-" + UUID.randomUUID();
    }
}
