# Transfer Contention Benchmark: `pessimistic` vs `conditional-update`

## 1. Objective

Compare the two balance-locking strategies implemented in `banking-service` (`POST /api/v1/transfers?strategy=<strategy>`) under identical workloads through `api-gateway` (`:8080`):

1. **`pessimistic` (default)** — Acquires row locks in ascending account UUID order in a single query (`SELECT a FROM Account a WHERE a.id IN (:ids) ORDER BY a.id ASC FOR UPDATE`), validates `balance >= debitAmount` in domain code, updates the cached balances, and inserts the `journal_entries` + `postings` rows.
2. **`conditional-update`** — Acquires row locks in ascending account UUID order and mutates balances via atomic SQL statements (`UPDATE accounts SET balance = balance - :debit, updated_at = :now WHERE id = :id AND balance >= :debit` and `UPDATE accounts SET balance = balance + :credit, updated_at = :now WHERE id = :id`), checking that affected row count is `1` before inserting the `journal_entries` + `postings` rows.

After every benchmark run, `GET /api/v1/ledger/reconciliation` is called to verify `balanced == true` and zero drift between `accounts.balance` and `SUM(postings)`.

---

## 2. Test Environment & Methodology

| Parameter | Value |
| --- | --- |
| **CPU** | `Intel64 Family 6 Model 140 Stepping 1, GenuineIntel` (8 logical cores) |
| **OS** | Windows 11 (`10.0.26300`) with Docker Desktop (WSL 2 backend) |
| **Runtime** | Eclipse Temurin OpenJDK 21 (`21-jre-alpine`), Spring Boot 4.1.2, PostgreSQL 17-alpine |
| **HikariCP Pool** | `maximum-pool-size = 32`, `connection-timeout = 2000 ms` |
| **Gateway Route** | `http://127.0.0.1:8080/api/v1/transfers?strategy=<pessimistic\|conditional-update>` |
| **Workloads** | (A) `hot_account`: all workers debit **1 shared source account** into per-worker sink accounts; (B) `many_accounts`: workers transfer across **disjoint account pairs** `(account[i], account[(i+1) % N])` |
| **Protocol** | 1,000 transfers per run, 3 interleaved rounds rotating strategy order (`Round 1: pessimistic -> conditional-update`, `Round 2: conditional-update -> pessimistic`, `Round 3: pessimistic -> conditional-update`) after a 2,000-request JVM warm-up |
| **Harness** | [`benchmarks/run_transfer_contention.py`](../../benchmarks/run_transfer_contention.py) and [`benchmarks/k6/transfer_contention.js`](../../benchmarks/k6/transfer_contention.js) |

---

## 3. Measured Results

### 3.1 Matched Pool Concurrency (`32 VUs`, matching `hikari.maximum-pool-size = 32`)

At 32 concurrent workers, every worker acquires a JDBC connection without queueing behind HikariCP's 2,000 ms connection timeout (`0` HTTP errors across all runs).

| Workload | Strategy | Median Throughput (`req/s`) | `p50` (`ms`) | `p95` (`ms`) | `p99` (`ms`) | HTTP Errors | Ledger Reconciled |
| --- | --- | ---: | ---: | ---: | ---: | ---: | :---: |
| **Hot Account** (1 source account) | `pessimistic` | **31.01** | **162.02** | **5,492.06** | **8,797.34** | 0 / 3,000 | `true` (0.0000 drift) |
| **Hot Account** (1 source account) | `conditional-update` | 25.10 | 177.72 | 5,937.42 | 11,083.65 | 0 / 3,000 | `true` (0.0000 drift) |
| **Many Accounts** (32 accounts) | `pessimistic` | **76.58** | **300.03** | **1,084.06** | **1,836.00** | 0 / 1,000 | `true` (0.0000 drift) |
| **Many Accounts** (32 accounts) | `conditional-update` | 67.75 | 308.53 | 1,230.75 | 2,336.80 | 0 / 1,000 | `true` (0.0000 drift) |

#### Per-Round Breakdown (`hot_account`, 32 VUs)

| Round | Execution Order | Strategy | `req/s` | `p50` (`ms`) | `p95` (`ms`) | `p99` (`ms`) | Errors | Reconciled |
| :---: | :---: | --- | ---: | ---: | ---: | ---: | ---: | :---: |
| 1 | 1st | `pessimistic` | 19.81 | 242.12 | 7,645.63 | 12,288.59 | 0 | `true` |
| 1 | 2nd | `conditional-update` | 20.23 | 177.72 | 8,246.16 | 13,356.64 | 0 | `true` |
| 2 | 1st | `conditional-update` | 25.10 | 212.41 | 5,937.42 | 11,083.65 | 0 | `true` |
| 2 | 2nd | `pessimistic` | 31.01 | 162.02 | 5,492.06 | 8,797.34 | 0 | `true` |
| 3 | 1st | `pessimistic` | 36.96 | 103.66 | 4,382.44 | 8,008.15 | 0 | `true` |
| 3 | 2nd | `conditional-update` | 38.57 | 90.81 | 4,485.92 | 7,020.75 | 0 | `true` |

---

### 3.2 Pool-Overload Concurrency (`100 VUs`, 3.1x HikariCP Pool Size)

At 100 concurrent workers against a 32-connection HikariCP pool (`connection-timeout = 2000 ms`), 68 workers queue for a JDBC connection. On the single hot account, transactions serialize on the hot `accounts` row lock plus the deferred `trg_verify_journal_entry_balanced` commit trigger; requests waiting longer than `2,000 ms` for a pool connection receive `503 database-unavailable` (`Retry-After: 5`) rather than exhausting JVM memory.

| Workload | Strategy | Median Throughput (`req/s`) | `p50` (`ms`) | `p95` (`ms`) | `p99` (`ms`) | 201 OK (3 rounds) | 503 Load-Shed (3 rounds) | Ledger Reconciled |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| **Hot Account** (100 VUs -> 1 account) | `pessimistic` | **30.91** | **2,134.43** | **7,076.93** | **12,621.20** | 1,519 | 1,481 | `true` (0.0000 drift) |
| **Hot Account** (100 VUs -> 1 account) | `conditional-update` | 27.83 | 2,183.31 | 8,088.21 | 13,388.12 | 1,443 | 1,557 | `true` (0.0000 drift) |
| **Many Accounts** (100 VUs -> 100 accounts) | `pessimistic` | 60.77 | **1,145.84** | 3,117.91 | 3,751.96 | 2,872 | 128 | `true` (0.0000 drift) |
| **Many Accounts** (100 VUs -> 100 accounts) | `conditional-update` | **72.07** | 1,152.38 | **2,473.06** | **3,104.94** | 2,845 | 155 | `true` (0.0000 drift) |

#### Per-Round Breakdown (100 VUs, 3 Interleaved Rounds)

| Workload | Round | Strategy | `req/s` | `p50` (`ms`) | `p95` (`ms`) | `p99` (`ms`) | `201 OK` | `503` | Reconciled |
| --- | :---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | :---: |
| `hot_account` | 1 | `pessimistic` | 26.30 | 2,402.15 | 11,340.55 | 17,475.67 | 330 | 670 | `true` |
| `hot_account` | 1 | `conditional-update` | 27.83 | 2,197.04 | 8,088.21 | 17,142.84 | 503 | 497 | `true` |
| `hot_account` | 2 | `conditional-update` | 27.02 | 2,183.31 | 8,964.56 | 12,117.60 | 422 | 578 | `true` |
| `hot_account` | 2 | `pessimistic` | 30.91 | 2,134.43 | 7,076.93 | 12,621.20 | 530 | 470 | `true` |
| `hot_account` | 3 | `pessimistic` | 33.49 | 2,111.31 | 6,545.43 | 9,821.87 | 659 | 341 | `true` |
| `hot_account` | 3 | `conditional-update` | 30.68 | 2,172.06 | 7,398.20 | 13,388.12 | 518 | 482 | `true` |
| `many_accounts` | 1 | `pessimistic` | 79.91 | 1,081.78 | 2,473.41 | 3,219.86 | 976 | 24 | `true` |
| `many_accounts` | 1 | `conditional-update` | 78.95 | 1,045.56 | 2,473.06 | 3,104.94 | 970 | 30 | `true` |
| `many_accounts` | 2 | `conditional-update` | 53.65 | 1,483.93 | 3,566.59 | 5,067.87 | 884 | 116 | `true` |
| `many_accounts` | 2 | `pessimistic` | 60.65 | 1,145.84 | 4,404.41 | 5,330.94 | 949 | 51 | `true` |
| `many_accounts` | 3 | `pessimistic` | 60.77 | 1,250.77 | 3,117.91 | 3,751.96 | 947 | 53 | `true` |
| `many_accounts` | 3 | `conditional-update` | 72.07 | 1,152.38 | 2,374.94 | 2,931.19 | 991 | 9 | `true` |

---

## 4. Analysis & Decision (Recorded in ADR 0005)

1. **Why both strategies serialize on the hot account**:
   - In a double-entry ledger where every transfer writes `postings` carrying `running_balance` (`balance_after`), the transaction must know the exact post-lock balance of both accounts before inserting `postings`.
   - `pessimistic` acquires both locks in a **single** round-trip (`SELECT ... WHERE id IN (:id1, :id2) ORDER BY id ASC FOR UPDATE`), computes `running_balance` in memory, and flushes updates at commit.
   - `conditional-update` still has to lock both rows in ascending UUID order to prevent `A -> B` / `B -> A` deadlocks and issues two separate `UPDATE accounts SET balance = ... WHERE id = ?` statements while holding the transaction open until `postings` and the `DEFERRABLE INITIALLY DEFERRED` balance trigger complete.
2. **Comparison**:
   - On the **hot account** workload, `pessimistic` achieves **23.5% higher median throughput at 32 VUs** (`31.01 req/s` vs `25.10 req/s`) and **11.1% higher median throughput at 100 VUs** (`30.91 req/s` vs `27.83 req/s`), with lower `p95` and `p99` tail latencies.
   - On the **many accounts** workload at 100 VUs, `conditional-update` is slightly faster (`72.07 req/s` vs `60.77 req/s`), while at 32 VUs `pessimistic` leads (`76.58 req/s` vs `67.75 req/s`).
   - Crucially, `pessimistic` generalizes cleanly to **4-posting cross-currency FX transfers** (which lock 4 accounts in ascending UUID order: source customer, destination customer, `FX_POSITION-sell`, `FX_POSITION-buy`), whereas `conditional-update` requires per-leg `UPDATE ... RETURNING` queries.
3. **Decision**: Keep `pessimistic` (`SELECT ... FOR UPDATE` in ascending UUID order) as the default transfer strategy, and retain `?strategy=conditional-update` for benchmarking and single-currency workloads.
