-- One roll-up of an unlocked system account's postings into a new checkpoint (ADR 0010). One statement, so the
-- horizon and the sum come from the same snapshot. Used by SystemBalanceCheckpoints and by the benchmark harness
-- (benchmarks/system_balance_read.py), so both run exactly this.
--
-- Parameters: :accountId (uuid), :id (uuid of the new checkpoint), :createdAt (timestamptz).
-- Inserts nothing when no posting finished since the previous checkpoint.
WITH horizon AS (
    SELECT pg_snapshot_xmin(pg_current_snapshot()) AS covered_below
),
previous AS (
    SELECT id, covered_below, balance, postings_covered
    FROM system_balance_checkpoints
    WHERE account_id = :accountId
    ORDER BY covered_below DESC
    LIMIT 1
),
added AS (
    SELECT COALESCE(SUM(p.amount), 0) AS amount, COUNT(*) AS postings
    FROM postings p, horizon h
    WHERE p.account_id = :accountId
      AND p.running_balance IS NULL
      AND (p.txid IS NULL OR p.txid < h.covered_below)
      AND (NOT EXISTS (SELECT 1 FROM previous) OR p.txid >= (SELECT covered_below FROM previous))
)
INSERT INTO system_balance_checkpoints (id, account_id, covered_below, balance, postings_covered,
                                        previous_checkpoint_id, created_at)
SELECT :id, :accountId, h.covered_below,
       COALESCE((SELECT balance FROM previous), 0) + a.amount,
       COALESCE((SELECT postings_covered FROM previous), 0) + a.postings,
       (SELECT id FROM previous), :createdAt
FROM horizon h, added a
WHERE a.postings > 0
  AND (NOT EXISTS (SELECT 1 FROM previous) OR h.covered_below > (SELECT covered_below FROM previous))
ON CONFLICT (account_id, covered_below) DO NOTHING
