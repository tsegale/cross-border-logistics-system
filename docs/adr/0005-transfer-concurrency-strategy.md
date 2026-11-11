# 5. Transfer concurrency control: ordered pessimistic locking vs conditional UPDATE

Date: 2026-11-08 · Status: Accepted · Revised 2026-11-11 (Phase 2: the benchmark is corrected and re-run)

## Context
Concurrent transfers contended on the same accounts expose two classic hazards:
1. **Lost updates / negative balances**: two concurrent transfers read `balance = 100.00`, both pass an in-memory
   check for `100.00`, and both debit the account to `-100.00`.
2. **AB-BA deadlocks (`SQLSTATE 40P01`)**: transfer 1 (`A -> B`) locks `A` then waits for `B`, while transfer 2
   (`B -> A`) locks `B` then waits for `A`.

Two strategies are implemented and selected at startup with `freightflow.banking.transfer-locking-strategy`
(`TRANSFER_LOCKING_STRATEGY`); `GET /actuator/info` reports the active one and an unknown value fails startup:
- **`pessimistic`**: sorts the participating account ids in PostgreSQL's unsigned UUID order, evicts pre-lock JPA
  snapshots (`flush(); clear()`), locks every row in one `SELECT ... WHERE id IN (:ids) ORDER BY id FOR UPDATE`,
  checks funds in the domain model and writes the entry and postings.
- **`conditional-update`** (same-currency transfers only): locks the rows in the same ascending order and moves the
  money with `UPDATE accounts SET balance = balance - :x WHERE id = :id AND balance - held_amount >= :x RETURNING
  balance`, then the credit, reading the running balances back from `RETURNING`.

**Correction.** The Phase 1 version of this ADR claimed `pessimistic` was 23.5% faster on a hot account. That
benchmark sent `?strategy=` as a query parameter that the controller never read, so both columns measured
`pessimistic`; the differences were noise and warm-up. Its "disjoint pairs" workload was also a ring in which every
account took part in two concurrent transfers.

## Benchmark (corrected)
Measured on an Intel Core i5-1135G7 laptop (4 cores / 8 threads, Windows 11, Docker Desktop on WSL 2 with 8 CPUs
and 3.7 GiB, host at 91% CPU with other applications running), 3 alternating rounds per strategy, banking-service
recreated before every block. Full method and per-run data: [`docs/benchmarks/transfer-contention.md`](../benchmarks/transfer-contention.md).

| Workload | VUs | `pessimistic` median req/s (range) | `conditional-update` median req/s (range) |
| --- | ---: | ---: | ---: |
| `disjoint_pairs` | 32 | 22.87 (22.45-46.93) | 50.72 (25.68-57.50) |
| `disjoint_pairs` | 100 | 29.03 (21.78-69.25) | 39.44 (35.34-54.65) |
| `hot_account` | 32 | 18.85 (13.80-20.86) | 16.93 (13.09-20.77) |
| `hot_account` | 100 | 21.56 (6.93-24.47) | 24.29 (20.97-24.29) |

The ranges overlap on every workload: the same strategy varies by more than 2x between rounds. The strategies
do not differ beyond round-to-round variation on this machine. Every run ended with a balanced reconciliation.

What the numbers do show is where the time goes: an uncontended transfer's SQL takes about 9 ms, but on this
laptop the stack is CPU-bound (banking 1.8-2.7 cores, PostgreSQL 1.3-1.7, the WSL load average at 32 on 8 CPUs,
JFR showing no hot method and GC threads waiting for CPU). The ~300 ms "uncontended p50" that Phase 1 reported was
CPU scheduling delay, not locking.

## Decision
- **`pessimistic` stays the default**, on the FX-path argument rather than on throughput: one ordered
  `SELECT ... FOR UPDATE` locks every participating row whatever the number of legs, so the same code serves
  same-currency transfers (2 rows), FX transfers through the `FX_POSITION` accounts, deposits, reversals and hold
  captures (ADR 0011). `conditional-update` only covers same-currency transfers.
- Always lock in ascending UUID order regardless of which side is debited, and clear the JPA persistence context
  before locking so Hibernate hydrates post-lock state.
- Keep `conditional-update` selectable at startup for benchmarking. It checks the available balance
  (`balance - held_amount`) like `pessimistic` does.
- Customer rows only: since ADR 0010 system accounts are not locked on the write path.

## Consequences
- **Zero deadlocks**: 100 concurrent `A -> B` and `B -> A` transfers complete with 0 `40P01` in
  `TransferConcurrencyIntegrationTest`.
- **Zero overdrafts or lost updates**: 200 concurrent 50.00 transfers draining 5,000.00 produce exactly 100
  `201 Created` and 100 `409 insufficient-funds`, ending at 0.00 with a clean reconciliation.
- Lock waits on customer rows are capped by `ledger.account-lock-timeout` (10 s) and answer a retryable `503
  lock-timeout` (ADR 0006).
- A strategy comparison that can separate a 20% difference needs a quiet, dedicated machine and more rounds; it is
  not worth doing while the system rows are the real hot spot.
