package com.freightflow.banking.approval;

import java.time.Instant;
import java.util.UUID;

/** Errors of the four-eyes flow (ADR 0021), each mapped to a problem type in docs/problems.md. */
public final class ApprovalExceptions {

    private ApprovalExceptions() {
    }

    public static class ApprovalNotFoundException extends RuntimeException {
        private final UUID approvalId;

        public ApprovalNotFoundException(UUID approvalId) {
            super("Approval request '" + approvalId + "' was not found");
            this.approvalId = approvalId;
        }

        public UUID getApprovalId() {
            return approvalId;
        }
    }

    /** The operator who asked for it tried to approve or reject it. */
    public static class FourEyesViolationException extends RuntimeException {
        private final UUID approvalId;

        public FourEyesViolationException(UUID approvalId) {
            super("Approval request " + approvalId + " must be decided by an operator other than the one who made it");
            this.approvalId = approvalId;
        }

        public UUID getApprovalId() {
            return approvalId;
        }
    }

    /** Already approved, rejected or expired. */
    public static class ApprovalNotPendingException extends RuntimeException {
        private final UUID approvalId;
        private final ApprovalStatus status;

        public ApprovalNotPendingException(UUID approvalId, ApprovalStatus status) {
            super("Approval request " + approvalId + " is " + status + ", not PENDING");
            this.approvalId = approvalId;
            this.status = status;
        }

        public UUID getApprovalId() {
            return approvalId;
        }

        public ApprovalStatus getStatus() {
            return status;
        }
    }

    /** Past its expiry; the sweeper marks it EXPIRED. A new request is needed. */
    public static class ApprovalExpiredException extends RuntimeException {
        private final UUID approvalId;
        private final Instant expiresAt;

        public ApprovalExpiredException(UUID approvalId, Instant expiresAt) {
            super("Approval request " + approvalId + " expired at " + expiresAt + "; request it again");
            this.approvalId = approvalId;
            this.expiresAt = expiresAt;
        }

        public UUID getApprovalId() {
            return approvalId;
        }

        public Instant getExpiresAt() {
            return expiresAt;
        }
    }

    /** A reversal of this entry is already waiting for a second operator. */
    public static class ReversalAlreadyRequestedException extends RuntimeException {
        private final UUID journalEntryId;

        public ReversalAlreadyRequestedException(UUID journalEntryId) {
            super("A reversal of journal entry " + journalEntryId + " is already waiting for approval");
            this.journalEntryId = journalEntryId;
        }

        public UUID getJournalEntryId() {
            return journalEntryId;
        }
    }

    /** The request body does not describe exactly one deposit or one reversal. */
    public static class InvalidApprovalRequestException extends RuntimeException {
        public InvalidApprovalRequestException(String message) {
            super(message);
        }
    }
}
