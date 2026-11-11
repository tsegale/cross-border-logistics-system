# Hot system accounts: `locked` vs `unlocked` vs `sharded` (ADR 0010)

## Question
Every deposit posts against its currency's `CLEARING` row, every FX transfer against the two `FX_POSITION` rows,
and every captured freight charge against `FREIGHT_REVENUE`. In Phase 1 each of those postings locked and updated the
system row, so they all queued on one row per currency. Does posting to system accounts without that lock remove the
bottleneck, and which of the two designs does it better?

- `locked`: Phase 1 behaviour, the "before".
- `unlocked`: the system row is neither locked nor updated; the posting is appended with no running balance and the
  balance is derived from the postings.
- `sharded`: one of 16 shard rows per system account, picked by a hash of the journal entry id, is locked and updated.

## Method
Same harness and method as the corrected transfer benchmark ([`transfer-contention.md`](transfer-contention.md)):
banking-service recreated with `SYSTEM_ACCOUNT_POSTING_MODE` before every block and the mode checked on
`GET /actuator/info`, 1,500 discarded warm-up requests, k6 closed-loop VUs through the gateway, and
`GET /api/v1/ledger/reconciliation` balanced after every run (the harness stops otherwise; it never had to).

- `deposits`: VU *i* deposits 1.00 EUR into its own customer account; every deposit posts against `CLEARING-EUR`.
- `fx_transfers`: VU *i* locks an FX quote and transfers 20.00 NAD to its own EUR account; every transfer posts
  against `FX_POSITION-NAD` and `FX_POSITION-EUR`. Only the transfer is timed.
- 3 rounds per mode with the order rotated (round 1: locked, unlocked, sharded; round 2: sharded, unlocked, locked;
  round 3: sharded, locked, unlocked). Round 2's `locked` block and round 3 ran in a second invocation, after
  banking-service took longer than the harness's startup window to restart on the loaded machine.

Machine: Intel Core i5-1135G7 laptop (4 cores / 8 threads), Windows 11 10.0.26300, Docker Desktop 29.7.2 on WSL 2
(8 CPUs, 3.7 GiB), with the host near full CPU load from other applications as in the transfer benchmark. HikariCP
pool 32, Spring Boot 4.1.2, Temurin 21, PostgreSQL 17.

## Results
Successful postings per second (`201` answers only). At 100 VUs the 32-connection pool sheds load with `503`, so raw
request rates there mostly count fast refusals:

| Workload | VUs | Mode | Median successful/s | Min-max successful/s (3 rounds) | Success rate |
| --- | ---: | --- | ---: | ---: | ---: |
| `deposits` | 32 | `locked` | 7.63 | 5.96-10.66 | 92% |
| `deposits` | 32 | `unlocked` | 24.05 | 22.47-24.60 | 100% |
| `deposits` | 32 | `sharded` | 17.83 | 8.18-19.94 | 97% |
| `deposits` | 100 | `locked` | 5.01 | 0.57-7.75 | 28% |
| `deposits` | 100 | `unlocked` | 5.26 | 3.03-28.82 | 63% |
| `deposits` | 100 | `sharded` | 12.88 | 6.57-28.39 | 72% |
| `fx_transfers` | 32 | `locked` | 4.37 | 3.52-6.50 | 82% |
| `fx_transfers` | 32 | `unlocked` | 9.05 | 6.05-14.96 | 98% |
| `fx_transfers` | 32 | `sharded` | 10.06 | 5.26-22.45 | 96% |
| `fx_transfers` | 100 | `locked` | 3.15 | 1.02-9.30 | 27% |
| `fx_transfers` | 100 | `unlocked` | 10.05 | 7.53-15.16 | 71% |
| `fx_transfers` | 100 | `sharded` | 5.97 | 5.24-8.39 | 60% |

All answers, as the harness reports them:

| Workload | VUs | Mode | Rounds | Median req/s | Min-max req/s | Median p50 ms | Median p95 ms | Median p99 ms | 201 | 503 |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `baseline` | 1 | `locked` | 3 | 3.55 | 3.14-3.81 | 105 | 1,170 | 3,049 | 899 | 0 |
| `baseline` | 1 | `unlocked` | 3 | 4.37 | 3.95-6.10 | 116 | 730 | 1,958 | 900 | 0 |
| `baseline` | 1 | `sharded` | 3 | 3.64 | 2.85-5.62 | 107 | 1,126 | 2,457 | 900 | 0 |
| `deposits` | 32 | `locked` | 3 | 8.22 | 7.04-10.87 | 1,497 | 15,050 | 15,795 | 2,734 | 86 |
| `deposits` | 32 | `unlocked` | 3 | 24.05 | 22.47-24.60 | 737 | 3,276 | 9,865 | 2,976 | 0 |
| `deposits` | 32 | `sharded` | 3 | 17.92 | 8.88-19.96 | 849 | 5,730 | 12,256 | 2,892 | 0 |
| `deposits` | 100 | `locked` | 3 | 13.72 | 9.84-19.18 | 2,768 | 18,199 | 20,478 | 827 | 1,795 |
| `deposits` | 100 | `unlocked` | 3 | 10.10 | 6.34-32.24 | 7,392 | 18,362 | 30,182 | 1,893 | 606 |
| `deposits` | 100 | `sharded` | 3 | 17.19 | 11.98-32.34 | 4,204 | 11,808 | 16,083 | 2,175 | 787 |
| `fx_transfers` | 32 | `locked` | 3 | 5.92 | 4.07-7.46 | 2,042 | 15,402 | 16,512 | 2,455 | 284 |
| `fx_transfers` | 32 | `unlocked` | 3 | 9.33 | 6.25-14.96 | 1,439 | 7,272 | 15,813 | 2,914 | 0 |
| `fx_transfers` | 32 | `sharded` | 3 | 10.06 | 5.88-22.45 | 1,353 | 5,950 | 8,677 | 2,871 | 27 |
| `fx_transfers` | 100 | `locked` | 3 | 15.76 | 9.25-18.68 | 3,034 | 14,455 | 15,993 | 808 | 1,927 |
| `fx_transfers` | 100 | `unlocked` | 3 | 14.33 | 12.29-18.26 | 3,447 | 11,909 | 17,151 | 2,144 | 780 |
| `fx_transfers` | 100 | `sharded` | 3 | 11.57 | 8.82-12.35 | 3,921 | 15,702 | 20,556 | 1,789 | 896 |

## Reading the numbers
- **Deposits at 32 VUs are the clean comparison**: the pool is not saturated and the only shared row is
  `CLEARING-EUR`. `unlocked` sustained 22.5-24.6 deposits/s in every round, `locked` 6.0-10.7/s: the ranges do not
  overlap, about 3x, and `unlocked` answered every request with `201` while `locked` failed 8% of them. `sharded`
  reached 17.8/s median but one round fell to 8.2/s, overlapping `locked`.
- **FX transfers** also lock two customer rows, so the system rows are a smaller share of the cost: both new modes
  roughly double `locked` (median 9.1 and 10.1 vs 4.4/s at 32 VUs), but their ranges overlap each other and, at the
  low end, `locked`'s best round.
- **At 100 VUs** every mode is limited by the connection pool on this machine; what changes is how many requests get
  through instead of `503`: 27-28% for `locked`, 60-72% for the other two.
- The same mode and workload varies by up to 4x between rounds on a laptop that is also running other work; only
  differences whose ranges do not overlap are treated as real.

## Decision (ADR 0010)
`unlocked` is the default. It is the only mode that beats `locked` with non-overlapping ranges, it needs no extra rows
or lock ordering, and it removes system rows from the write path entirely. `sharded` stays selectable for
benchmarking; its advantage, a constant-cost balance read, is not needed while system balances are read only by
reconciliation and operators.

## Reading an unlocked balance at a million postings (Phase 3)
`unlocked` made the balance read a sum over every unlocked posting. Measured with
[`benchmarks/system_balance_read.py`](../../benchmarks/system_balance_read.py) before and after the checkpoint
roll-up (ADR 0010 revision): 1,000,000 postings on `CLEARING-EUR`, server execution time from `EXPLAIN ANALYZE`,
30 warm runs, PostgreSQL 17.11, same laptop (host CPU at 67% from other applications at the start).

| Read | Median ms | p95 ms | Min-max ms |
| --- | ---: | ---: | ---: |
| Without a checkpoint | 175.449 | 218.532 | 145.728-265.779 |
| With a checkpoint, 1,000 postings after it | 0.484 | 0.834 | 0.348-1.328 |

The roll-up that produced the first checkpoint took 1,809 ms. Raw results: `benchmarks/out/system-balance-read.json`
(not committed; the hosted-runner workflow uploads them as an artifact).
