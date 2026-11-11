# 10. System accounts are posted to without a row lock

Date: 2026-11-11 · Status: Accepted

## Context
Customer accounts need a lock: a customer's balance may not go negative (ADR 0004) and since ADR 0011 may not go
below its holds, so a debit must read the current balance under `SELECT ... FOR UPDATE` (ADR 0005). Phase 1 treated
system accounts the same way. Every deposit locked `CLEARING-<ccy>`, every FX transfer `FX_POSITION-<sell>` and
`FX_POSITION-<buy>`, and every hold capture credits `FREIGHT_REVENUE-<ccy>`: one row per currency through which the
whole platform's money movements queued. System accounts have no balance constraint to protect.

## Options
1. **Keep locking** (`locked`): simple, with a running balance on every posting, but one hot row per currency.
2. **Unlocked, derived balance** (`unlocked`): the system row is never locked or updated; its postings are appended
   with `running_balance = NULL` and its balance is the sum of those postings (or rolled up asynchronously later).
3. **Sharded sub-balances** (`sharded`): N shard rows per system account; an entry locks the one picked by a hash of
   its id. The balance is the sum of the shards; contention drops roughly N-fold but every entry still takes a lock.

## Decision
`unlocked`, chosen by measurement ([`docs/benchmarks/hot-accounts.md`](../benchmarks/hot-accounts.md)). On the
benchmark laptop, deposits into many customer accounts (all posting against one `CLEARING` row) went from 6.0-10.7/s
with `locked` to 22.5-24.6/s with `unlocked` across three rounds, with every request answered `201`; `sharded`
reached 17.8/s median but overlapped `locked` in one round. FX transfers roughly doubled in both new modes.

- `freightflow.banking.system-accounts.posting-mode` (`SYSTEM_ACCOUNT_POSTING_MODE`) selects the mode at startup,
  `unlocked` by default; `/actuator/info` reports it. `locked` and `sharded` stay available for benchmarks.
- **Write path** (`LedgerService.recordEntry`): lock only the entry's customer rows (ascending id, ADR 0005), read the
  system rows without a lock, check they are active and in the posting's currency, and append. In `sharded` mode the
  shard rows are locked after the account rows, in ascending account id, so the global lock order never inverts.
  The posting's foreign key takes `FOR KEY SHARE` on the system row, which does not conflict with a writer's
  `FOR NO KEY UPDATE`.
- **What replaces the running balance on system postings**: nothing on the posting (`running_balance` is `NULL`;
  customer postings always keep theirs, enforced by a trigger). A system account's balance is `accounts.balance`
  (postings made while the row was locked) `+ SUM(account_balance_shards)` (sharded postings)
  `+ SUM(unlocked postings)`, read by `SystemAccountBalances` through a covering partial index. Customer statements
  are unchanged.
- **Reconciliation stays exact**: `accounts.balance` is checked against the postings made with the row locked, each
  shard against the postings made to it, `held_amount` against active holds (ADR 0011), and the per-currency trial
  balance covers every posting.

## Consequences
- Deposits, FX transfers and captures no longer queue on a system row; customer rows are still serialised per
  customer, which is contention that has to exist.
- Reading a system account's balance is an index-only scan over its unlocked postings and grows with them. That is
  fine while only reconciliation and operators read it; a periodic roll-up into a checkpoint row is the next step if
  a hot path ever needs it.
- A statement of a system account has no running balance per posting.

## Revision (Phase 3): checkpointed balances

Date: 2026-11-11

The open point above, made concrete: with 1,000,000 unlocked postings on `CLEARING-EUR`, the balance read took
175 ms (median, 146-266 ms over 30 warm runs) and grows linearly with the postings.

### Decision
A periodic roll-up writes the sum so far into `system_balance_checkpoints` (V6), append-only; the balance is the
latest checkpoint plus the postings after it.

- **What "after" means.** Not a posting id or a timestamp: both are assigned before commit, so a transaction that
  started before a roll-up can commit after it and its posting would be skipped for ever. Each posting records the
  transaction that wrote it (`postings.txid xid8 DEFAULT pg_current_xact_id()`; `xid8` never wraps). A checkpoint
  covers exactly the postings of transactions below `pg_snapshot_xmin(pg_current_snapshot())`, read in the same
  statement as the sum: every transaction below that point has finished, so nothing can later appear behind the
  checkpoint. Postings written before V6 have a `NULL` txid and are covered by the first checkpoint.
- **The roll-up** (`sql/system-balance-checkpoint.sql`, run by `SystemBalanceCheckpoints` every 60 s on every
  instance) is incremental: previous checkpoint + postings with `txid` in `[previous horizon, new horizon)`. A
  transaction-scoped advisory lock per account lets one instance do the work, and `UNIQUE (account_id,
  covered_below)` makes a duplicate a no-op. Nothing is written when no posting finished since the last one.
- **The read** is two statements, the latest checkpoint and then a range scan over
  `idx_postings_unlocked_by_account_txid (account_id, txid) INCLUDE (amount)`. Exact for the same reason: every
  transaction below the horizon finished before the checkpoint was written.
- **Reconciliation** recomputes every account's latest checkpoint from the postings it covers; a forged or wrong
  checkpoint is a discrepancy (`#checkpoint`). Checkpoints are append-only under the ledger's trigger and refused for
  customer accounts.
- **Tested**: a deposit whose transaction is still open while the roll-up runs, and commits after it, is counted
  (`SystemBalanceCheckpointsIntegrationTest`).

### Measured
`benchmarks/system_balance_read.py`: PostgreSQL 17.11 in a throwaway container with banking's migrations,
1,000,000 unlocked postings per account, then 1,000 more after the checkpoint; server execution time from
`EXPLAIN ANALYZE`, 30 warm runs. Intel Core i5-1135G7 laptop, Docker Desktop on WSL 2, host CPU at 67% from other
applications when the run started.

| Read | Median ms | p95 ms | Min-max ms |
| --- | ---: | ---: | ---: |
| Without a checkpoint: sum over 1,000,000 postings | 175.449 | 218.532 | 145.728-265.779 |
| Latest checkpoint lookup | 0.058 | 0.106 | 0.042-0.183 |
| With a checkpoint: plus the 1,000 postings after it | 0.484 | 0.834 | 0.348-1.328 |

The first roll-up over all 1,000,000 postings took 1,809 ms; later ones only sum the postings since the previous
checkpoint. Both reads returned the same balance (1,001,000.0000).

### Consequences
- The read stays proportional to the postings since the last roll-up (about a minute of traffic), not to history.
- One more index on `postings` and one more column per posting.
- A long-running transaction holds the horizon back, so postings after it wait for a later checkpoint; the balance
  is still exact, the read just sums a few more rows.
