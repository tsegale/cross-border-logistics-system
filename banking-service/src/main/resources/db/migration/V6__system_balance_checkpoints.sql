-- Checkpointed balances for unlocked system accounts (ADR 0010, revised in Phase 3).
--
-- An unlocked system account's balance was the sum of all its unlocked postings, which grows with every deposit,
-- FX transfer and capture. A roll-up now records the sum so far in an append-only checkpoint, and the balance is the
-- latest checkpoint plus the postings made after it.
--
-- "After it" cannot be decided by posting id or time: both are assigned before commit, so a transaction that started
-- earlier can commit after a checkpoint was taken, and its posting would be missed. Each posting therefore records
-- the id of the transaction that wrote it (xid8, never wraps). A checkpoint covers exactly the postings of
-- transactions below its horizon, pg_snapshot_xmin(pg_current_snapshot()) at the time it was taken: every
-- transaction below that point had already finished, so no posting can ever appear behind a checkpoint.

-- NULL for postings written before V6: all of them committed before this migration ran.
ALTER TABLE postings ADD COLUMN txid xid8;
ALTER TABLE postings ALTER COLUMN txid SET DEFAULT pg_current_xact_id();

-- The unlocked postings of an account after a checkpoint: a range scan on (account_id, txid), index only.
CREATE INDEX idx_postings_unlocked_by_account_txid
    ON postings (account_id, txid) INCLUDE (amount)
    WHERE running_balance IS NULL;
DROP INDEX idx_postings_unlocked_by_account;

CREATE TABLE system_balance_checkpoints (
    id                     UUID PRIMARY KEY,
    account_id             UUID           NOT NULL REFERENCES accounts (id),
    -- Covers every unlocked posting of the account whose txid is below this (or NULL).
    covered_below          xid8           NOT NULL,
    balance                NUMERIC(19, 4) NOT NULL,
    postings_covered       BIGINT         NOT NULL,
    previous_checkpoint_id UUID REFERENCES system_balance_checkpoints (id),
    created_at             TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_system_balance_checkpoints_horizon UNIQUE (account_id, covered_below),
    CONSTRAINT chk_system_balance_checkpoints_count CHECK (postings_covered >= 0)
);

-- Append-only, like the ledger it summarises.
CREATE TRIGGER trg_system_balance_checkpoints_append_only
    BEFORE UPDATE OR DELETE ON system_balance_checkpoints
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_mutation();

-- Customer balances are never derived: they are the locked row (ADR 0005).
CREATE OR REPLACE FUNCTION reject_customer_checkpoint()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND account_type = 'CUSTOMER') THEN
        RAISE EXCEPTION 'account % is a customer account and cannot have a balance checkpoint', NEW.account_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_system_balance_checkpoints_system_only
    BEFORE INSERT ON system_balance_checkpoints
    FOR EACH ROW
    EXECUTE FUNCTION reject_customer_checkpoint();
