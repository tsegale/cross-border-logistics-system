# 5. Transfer concurrency control: ordered pessimistic locking vs conditional UPDATE

Date: 2026-11-08 · Status: Accepted

## Context
Concurrent transfers contended on the same accounts expose two classic hazards:
1. **Lost updates / negative balances**: Two concurrent transfers read `balance = 100.00`, both pass an in-memory check for `100.00`, and both debit the account to `-100.00`.
2. **AB-BA deadlocks (`SQLSTATE 40P01`)**: Transfer 1 (`A -> B`) locks `A` then waits for `B`, while Transfer 2 (`B -> A`) locks `B` then waits for `A`.

We implemented two concurrency strategies behind `POST /api/v1/transfers?strategy=<pessimistic|conditional-update>` and benchmarked both across hot-account and many-account workloads:
- **`pessimistic`**: Sorts all participating account UUIDs in ascending order, evicts any pre-lock JPA 1st-level cache snapshots (`entityManager.flush(); entityManager.clear()`), locks the rows in a single `SELECT a FROM Account a WHERE a.id IN :ids ORDER BY a.id ASC FOR UPDATE`, validates sufficient funds, updates balances, and inserts the journal entry and postings.
- **`conditional-update`**: Sorts participating account UUIDs in ascending order to prevent deadlocks, then executes `UPDATE accounts SET balance = balance - :debit WHERE id = :id AND balance >= :debit` (checking affected rows == 1) and `UPDATE accounts SET balance = balance + :credit WHERE id = :id`, reading back the post-update balances to populate `postings.running_balance`.

## Benchmark Summary

Measured on `Intel64 Family 6 Model 140 Stepping 1, GenuineIntel` (8 logical cores), Windows 11 (`10.0.26300`), Docker Desktop / WSL 2, PostgreSQL 17, Eclipse Temurin 21, HikariCP `maximum-pool-size = 32` (full report in [`docs/benchmarks/transfer-contention.md`](../benchmarks/transfer-contention.md)):

| Workload | Concurrency | Strategy | Median `req/s` | `p50` (`ms`) | `p95` (`ms`) | `p99` (`ms`) | Reconciled |
| --- | :---: | --- | ---: | ---: | ---: | ---: | :---: |
| **Hot Account** (1 source account) | 32 VUs | `pessimistic` | **31.01** | **162.02** | **5,492.06** | **8,797.34** | `true` |
| **Hot Account** (1 source account) | 32 VUs | `conditional-update` | 25.10 | 177.72 | 5,937.42 | 11,083.65 | `true` |
| **Many Accounts** (disjoint pairs) | 32 VUs | `pessimistic` | **76.58** | **300.03** | **1,084.06** | **1,836.00** | `true` |
| **Many Accounts** (disjoint pairs) | 32 VUs | `conditional-update` | 67.75 | 308.53 | 1,230.75 | 2,336.80 | `true` |
| **Hot Account** (1 source account) | 100 VUs | `pessimistic` | **30.91** | **2,134.43** | **7,076.93** | **12,621.20** | `true` |
| **Hot Account** (100 VUs) | 100 VUs | `conditional-update` | 27.83 | 2,183.31 | 8,088.21 | 13,388.12 | `true` |
| **Many Accounts** (100 accounts) | 100 VUs | `pessimistic` | 60.77 | **1,145.84** | 3,117.91 | 3,751.96 | `true` |
| **Many Accounts** (100 accounts) | 100 VUs | `conditional-update` | **72.07** | 1,152.38 | **2,473.06** | **3,104.94** | `true` |

## Decision
- Default `POST /api/v1/transfers` to **`pessimistic`** (`SELECT ... WHERE id IN (:ids) ORDER BY id ASC FOR UPDATE`).
- Always lock participating accounts in **ascending UUID order** regardless of which account is the debit or credit leg, and always clear the JPA persistence context immediately before acquiring pessimistic locks so Hibernate hydrates post-lock database state.
- Retain `?strategy=conditional-update` as a selectable query parameter for benchmarking and single-currency workloads.

## Consequences
- **Zero deadlocks**: 100 concurrent `A -> B` and `B -> A` transfers complete with 0 `40P01` deadlocks in `TransferConcurrencyIntegrationTest`.
- **Zero overdrafts or lost updates**: 200 concurrent `10.00` transfers draining an account with `1,000.00` produce exactly 100 `201 Created` and 100 `409 insufficient-funds` responses, ending at `0.0000` with zero reconciliation drift.
- **Unified multi-leg locking**: `pessimistic` locks 2 accounts (single-currency) or 4 accounts (cross-currency FX transfer via `FX_POSITION` accounts) in a single ordered SQL round-trip.
