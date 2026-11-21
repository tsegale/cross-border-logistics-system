-- Four-eyes approvals (ADR 0021): an operator requests a deposit or a reversal, a different operator approves or
-- rejects it, and only the approval posts the journal entry.

CREATE TABLE approval_requests (
    id                     UUID PRIMARY KEY,
    kind                   VARCHAR(16)   NOT NULL,
    status                 VARCHAR(16)   NOT NULL,
    -- DEPOSIT: into this customer account, from CLEARING
    account_id             UUID REFERENCES accounts (id),
    amount                 NUMERIC(19, 4),
    currency               VARCHAR(3),
    reference              VARCHAR(160),
    -- REVERSAL: of this entry
    journal_entry_id       UUID REFERENCES journal_entries (id),
    reason                 VARCHAR(160),
    -- The maker (ADR 0020)
    requested_by_subject   VARCHAR(64)   NOT NULL,
    requested_by_client_id VARCHAR(64)   NOT NULL,
    requested_request_id   VARCHAR(64),
    requested_at           TIMESTAMPTZ   NOT NULL,
    expires_at             TIMESTAMPTZ   NOT NULL,
    -- The checker. An expired request has a decision time but nobody who decided.
    decided_by_subject     VARCHAR(64),
    decided_by_client_id   VARCHAR(64),
    decided_request_id     VARCHAR(64),
    decided_at             TIMESTAMPTZ,
    decision_comment       VARCHAR(500),
    posted_entry_id        UUID UNIQUE REFERENCES journal_entries (id),

    CONSTRAINT chk_approval_kind CHECK (kind IN ('DEPOSIT', 'REVERSAL')),
    CONSTRAINT chk_approval_currency CHECK (currency IS NULL OR currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_approval_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'EXPIRED')),
    CONSTRAINT chk_approval_deposit CHECK (kind <> 'DEPOSIT' OR (account_id IS NOT NULL AND amount > 0
        AND currency IS NOT NULL AND reference IS NOT NULL AND journal_entry_id IS NULL AND reason IS NULL)),
    CONSTRAINT chk_approval_reversal CHECK (kind <> 'REVERSAL' OR (journal_entry_id IS NOT NULL AND reason IS NOT NULL
        AND account_id IS NULL AND amount IS NULL AND currency IS NULL AND reference IS NULL)),
    CONSTRAINT chk_approval_expiry CHECK (expires_at > requested_at),
    CONSTRAINT chk_approval_decided CHECK ((status = 'PENDING') = (decided_at IS NULL)),
    CONSTRAINT chk_approval_decider CHECK ((status IN ('APPROVED', 'REJECTED'))
        = (decided_by_subject IS NOT NULL AND decided_by_client_id IS NOT NULL)),
    -- The point of it all: the maker never checks their own request.
    CONSTRAINT chk_approval_four_eyes CHECK (decided_by_subject IS NULL OR decided_by_subject <> requested_by_subject),
    CONSTRAINT chk_approval_posted CHECK ((status = 'APPROVED') = (posted_entry_id IS NOT NULL))
);

CREATE INDEX idx_approval_requests_pending ON approval_requests (expires_at) WHERE status = 'PENDING';
CREATE INDEX idx_approval_requests_status ON approval_requests (status, requested_at);
-- One open reversal request per entry: a second is a 409, not a second checker's chance to post it twice.
CREATE UNIQUE INDEX uq_approval_requests_pending_reversal ON approval_requests (journal_entry_id)
    WHERE kind = 'REVERSAL' AND status = 'PENDING';

-- The entry an approval posted points back at it: at most one entry per approval, whatever races.
ALTER TABLE journal_entries ADD COLUMN approval_id UUID UNIQUE REFERENCES approval_requests (id);

-- No code path posts a deposit or a reversal on one person's word: the database refuses one without an approval.
-- Entries written before V8 keep what they have; the trigger only sees new rows.
CREATE OR REPLACE FUNCTION require_approval()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.entry_type IN ('DEPOSIT', 'REVERSAL') AND NEW.approval_id IS NULL THEN
        RAISE EXCEPTION '% entry % has no approval: deposits and reversals need a second operator (ADR 0021)',
            NEW.entry_type, NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_journal_entries_require_approval
    BEFORE INSERT ON journal_entries
    FOR EACH ROW
    EXECUTE FUNCTION require_approval();
