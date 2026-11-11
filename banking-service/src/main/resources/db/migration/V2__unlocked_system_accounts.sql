-- Hot system accounts (ADR 0010). Every deposit posts against its currency's CLEARING row, every FX transfer
-- against the FX_POSITION rows and every captured freight charge against FREIGHT_REVENUE. Locking and updating
-- those rows serialised the platform. Customer rows keep their lock (a customer may not go negative); system rows
-- are posted to without one, in one of two ways that can be compared by benchmark:
--
--   unlocked  the posting is appended with running_balance NULL and touches no balance row; the balance is
--             derived from the postings.
--   sharded   the posting updates one of N shard rows (chosen by a hash of the journal entry id) and records the
--             shard and that shard's running balance.
--
-- A system account's balance is therefore always
--   accounts.balance                       (postings made while the row was locked, Phase 1)
--   + SUM(account_balance_shards.balance)  (sharded postings)
--   + SUM(postings.amount WHERE running_balance IS NULL)   (unlocked postings)
-- and reconciliation checks each part against its own postings.

ALTER TABLE postings ALTER COLUMN running_balance DROP NOT NULL;
ALTER TABLE postings ADD COLUMN shard SMALLINT;
ALTER TABLE postings ADD CONSTRAINT chk_postings_shard_has_running_balance
    CHECK (shard IS NULL OR (shard >= 0 AND running_balance IS NOT NULL));

CREATE TABLE account_balance_shards (
    account_id UUID          NOT NULL REFERENCES accounts (id),
    shard      SMALLINT      NOT NULL,
    balance    NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    updated_at TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (account_id, shard),
    CONSTRAINT chk_account_balance_shards_shard CHECK (shard >= 0 AND shard < 256)
);

-- Shards exist for system accounts only. Customer balances never shard: the non-negative check needs one row.
CREATE OR REPLACE FUNCTION reject_customer_shard()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND account_type = 'CUSTOMER') THEN
        RAISE EXCEPTION 'account % is a customer account and cannot have balance shards', NEW.account_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_account_balance_shards_system_only
    BEFORE INSERT ON account_balance_shards
    FOR EACH ROW
    EXECUTE FUNCTION reject_customer_shard();

-- 16 shards per system account, created up front so the write path only ever UPDATEs.
INSERT INTO account_balance_shards (account_id, shard, balance, updated_at)
SELECT a.id, s.shard, 0.0000, now()
FROM accounts a
CROSS JOIN generate_series(0, 15) AS s(shard)
WHERE a.account_type <> 'CUSTOMER';

-- A customer posting always carries its running balance and never a shard.
CREATE OR REPLACE FUNCTION verify_customer_posting()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF (NEW.running_balance IS NULL OR NEW.shard IS NOT NULL)
       AND EXISTS (SELECT 1 FROM accounts WHERE id = NEW.account_id AND account_type = 'CUSTOMER') THEN
        RAISE EXCEPTION 'customer posting % must carry a running balance and no shard', NEW.id
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_postings_customer_running_balance
    BEFORE INSERT ON postings
    FOR EACH ROW
    EXECUTE FUNCTION verify_customer_posting();

-- Derived balance of an unlocked system account: an index-only scan over its unlocked postings.
CREATE INDEX idx_postings_unlocked_by_account
    ON postings (account_id) INCLUDE (amount)
    WHERE running_balance IS NULL;
CREATE INDEX idx_postings_sharded_by_account
    ON postings (account_id, shard) INCLUDE (amount)
    WHERE shard IS NOT NULL;
