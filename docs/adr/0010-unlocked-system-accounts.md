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
