# Transfer contention benchmark: `pessimistic` vs `conditional-update`

## What changed since Phase 1
The Phase 1 report compared one strategy with itself. `TransferController` never read the `?strategy=` parameter
the harness sent, so every run used the default, `pessimistic`; the differences it reported were noise and warm-up
(the second run of each round was faster). Its `many_accounts` workload was also a ring (`account[i] ->
account[i+1]`), so every account was in two concurrent transfers, not a set of disjoint pairs.

The harness now:
- switches the strategy by recreating banking-service with `TRANSFER_LOCKING_STRATEGY` and checks that
  `GET /actuator/info` reports it before every block (an unknown value now fails startup);
- generates load with k6 in closed-loop VUs, where VU *i* owns its accounts (`disjoint_pairs`: VU *i* transfers
  `pairs[i][0] -> pairs[i][1]`, no account shared between VUs);
- warms the JVM up with 1,500 discarded requests after every restart;
- collects, per run, server-side latency (`http_server_requests`), HikariCP acquire and usage time,
  `pg_stat_statements` (`benchmarks/compose.bench.yml`) and `docker stats` CPU.

Harness: [`benchmarks/run_transfer_contention.py`](../../benchmarks/run_transfer_contention.py) and
[`benchmarks/k6/transfer_contention.js`](../../benchmarks/k6/transfer_contention.js). Raw results:
`benchmarks/out/transfer_contention_results.json` (not committed).

## Machine and method
| Parameter | Value |
| --- | --- |
| CPU | Intel Core i5-1135G7 @ 2.40 GHz, 4 cores / 8 threads (laptop, on AC, Windows "Balanced" power plan) |
| OS | Windows 11 (10.0.26300), Docker Desktop 29.7.2 on WSL 2 (8 CPUs, 3.7 GiB for the VM) |
| Host load | 91% CPU reported by Windows during the runs: Docker Desktop, Chrome and VS Code were running |
| Stack | Default compose stack (gateway, banking, logistics, two PostgreSQL 17) plus `compose.bench.yml`; k6 v2.3.0 on the host |
| Service | Spring Boot 4.1.2, Temurin 21 JRE, HikariCP `maximum-pool-size = 32`, `connection-timeout = 2000 ms` |
| Load | Through the gateway (`127.0.0.1:8080`), 1.00 EUR transfers; `baseline` = 300 requests from 1 VU; other runs = 1,000 requests (10 per VU at 100 VUs, 31 per VU at 32 VUs) |
| Rounds | 3, strategy order alternating (A B, B A, A B), banking-service recreated before every block |
| Check | `GET /api/v1/ledger/reconciliation` balanced after every run (the harness stops otherwise): it was, every time |

## Results
Median over the 3 rounds, with the range of the three throughputs:

| Workload | VUs | Strategy | Median req/s | Min-max req/s (3 rounds) | p50 ms | p95 ms | p99 ms | Server mean ms | 201 | 503 |
| --- | ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| `baseline` | 1 | `conditional-update` | 7.06 | 5.66-12.72 | 75.3 | 361.7 | 1,054.6 | 115.8 | 900 | 0 |
| `baseline` | 1 | `pessimistic` | 5.40 | 4.17-5.78 | 88.8 | 496.6 | 1,894.2 | 145.2 | 900 | 0 |
| `disjoint_pairs` | 32 | `conditional-update` | 50.72 | 25.68-57.50 | 331.8 | 2,494.8 | 3,687.5 | 425.8 | 2,976 | 0 |
| `disjoint_pairs` | 32 | `pessimistic` | 22.87 | 22.45-46.93 | 645.7 | 4,133.8 | 7,584.9 | 1,019.1 | 2,976 | 0 |
| `disjoint_pairs` | 100 | `conditional-update` | 39.44 | 35.34-54.65 | 1,315.3 | 7,079.6 | 10,976.5 | 1,587.7 | 2,873 | 127 |
| `disjoint_pairs` | 100 | `pessimistic` | 29.03 | 21.78-69.25 | 1,785.0 | 6,334.1 | 15,197.2 | 2,009.8 | 2,854 | 146 |
| `hot_account` | 32 | `conditional-update` | 16.93 | 13.09-20.77 | 711.3 | 7,188.2 | 12,687.6 | 1,732.2 | 2,976 | 0 |
| `hot_account` | 32 | `pessimistic` | 18.85 | 13.80-20.86 | 446.0 | 6,098.2 | 11,998.6 | 1,422.3 | 2,976 | 0 |
| `hot_account` | 100 | `conditional-update` | 24.29 | 20.97-24.29 | 2,481.4 | 8,743.1 | 16,565.0 | 3,251.0 | 1,676 | 1,324 |
| `hot_account` | 100 | `pessimistic` | 21.56 | 6.93-24.47 | 2,632.9 | 9,644.5 | 15,881.2 | 3,444.1 | 1,571 | 1,323 |

### Per run

| Round | Strategy | Workload | VUs | req/s | p50 ms | Server mean ms | Hikari acquire ms | Hikari usage ms | banking CPU % | Postgres CPU % | gateway CPU % |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | `pessimistic` | `baseline` | 1 | 4.17 | 85.0 | 189.0 | 7.8 | 143.8 | 144 | 39 | 25 |
| 1 | `pessimistic` | `disjoint_pairs` | 32 | 22.45 | 645.7 | 1,019.1 | 27.6 | 849.0 | 273 | 135 | 82 |
| 1 | `pessimistic` | `disjoint_pairs` | 100 | 21.78 | 1,785.0 | 2,858.2 | 596.0 | 1,154.5 | 285 | 239 | 141 |
| 1 | `pessimistic` | `hot_account` | 32 | 13.80 | 446.0 | 2,106.2 | 11.8 | 2,067.3 | 142 | 81 | 48 |
| 1 | `pessimistic` | `hot_account` | 100 | 6.93 | 2,681.1 | 7,225.6 | 1,566.5 | 8,472.9 | 162 | 91 | 60 |
| 1 | `conditional-update` | `baseline` | 1 | 5.66 | 75.3 | 131.6 | 8.7 | 100.2 | 126 | 41 | 28 |
| 1 | `conditional-update` | `disjoint_pairs` | 32 | 25.68 | 449.4 | 885.8 | 42.5 | 739.5 | 203 | 156 | 48 |
| 1 | `conditional-update` | `disjoint_pairs` | 100 | 35.34 | 1,745.6 | 1,840.3 | 521.7 | 802.0 | 237 | 128 | 79 |
| 1 | `conditional-update` | `hot_account` | 32 | 13.09 | 785.8 | 2,277.0 | 2.4 | 2,257.3 | 150 | 120 | 44 |
| 1 | `conditional-update` | `hot_account` | 100 | 24.29 | 2,481.4 | 2,942.0 | 1,534.2 | 2,010.0 | 97 | 98 | 45 |
| 2 | `conditional-update` | `baseline` | 1 | 12.72 | 62.9 | 65.7 | 4.6 | 51.4 | 137 | 40 | 10 |
| 2 | `conditional-update` | `disjoint_pairs` | 32 | 50.72 | 277.1 | 425.8 | 6.7 | 361.3 | 260 | 137 | 59 |
| 2 | `conditional-update` | `disjoint_pairs` | 100 | 39.44 | 1,315.3 | 1,587.7 | 311.5 | 709.5 | 224 | 137 | 78 |
| 2 | `conditional-update` | `hot_account` | 32 | 20.77 | 663.3 | 1,286.4 | 2.8 | 1,271.6 | 116 | 98 | 25 |
| 2 | `conditional-update` | `hot_account` | 100 | 24.29 | 2,582.2 | 3,251.0 | 1,670.1 | 2,347.5 | 171 | 66 | 42 |
| 2 | `pessimistic` | `baseline` | 1 | 5.40 | 88.8 | 145.2 | 9.0 | 115.0 | 142 | 54 | 16 |
| 2 | `pessimistic` | `disjoint_pairs` | 32 | 46.93 | 407.8 | 500.8 | 7.8 | 444.2 | 184 | 126 | 46 |
| 2 | `pessimistic` | `disjoint_pairs` | 100 | 69.25 | 802.8 | 1,041.2 | 380.2 | 436.6 | 264 | 148 | 54 |
| 2 | `pessimistic` | `hot_account` | 32 | 20.86 | 403.9 | 1,377.0 | 3.4 | 1,362.6 | 162 | 86 | 36 |
| 2 | `pessimistic` | `hot_account` | 100 | 24.47 | 2,241.0 | 3,038.7 | 1,655.1 | 2,043.2 | 222 | 88 | 122 |
| 3 | `pessimistic` | `baseline` | 1 | 5.78 | 99.4 | 144.9 | 10.1 | 113.1 | 138 | 76 | 13 |
| 3 | `pessimistic` | `disjoint_pairs` | 32 | 22.87 | 853.3 | 1,133.8 | 22.9 | 966.0 | 272 | 172 | 58 |
| 3 | `pessimistic` | `disjoint_pairs` | 100 | 29.03 | 1,799.2 | 2,009.8 | 480.3 | 955.3 | 268 | 177 | 130 |
| 3 | `pessimistic` | `hot_account` | 32 | 18.85 | 461.8 | 1,422.3 | 2.6 | 1,404.2 | 157 | 102 | 24 |
| 3 | `pessimistic` | `hot_account` | 100 | 21.56 | 2,632.9 | 3,444.1 | 1,692.2 | 2,942.2 | 169 | 76 | 68 |
| 3 | `conditional-update` | `baseline` | 1 | 7.06 | 83.3 | 115.8 | 6.5 | 95.2 | 172 | 54 | 19 |
| 3 | `conditional-update` | `disjoint_pairs` | 32 | 57.50 | 331.8 | 389.3 | 4.8 | 344.4 | 230 | 153 | 54 |
| 3 | `conditional-update` | `disjoint_pairs` | 100 | 54.65 | 902.7 | 1,016.7 | 348.4 | 482.4 | 243 | 105 | 50 |
| 3 | `conditional-update` | `hot_account` | 32 | 16.93 | 711.3 | 1,732.2 | 2.9 | 1,712.1 | 134 | 102 | 35 |
| 3 | `conditional-update` | `hot_account` | 100 | 20.97 | 2,424.6 | 3,260.8 | 1,697.4 | 2,743.8 | 177 | 84 | 50 |

## Reading the numbers
**The strategies do not differ beyond round-to-round variation.** The same strategy on the same workload varies by
more than 2x between rounds: `pessimistic` on `disjoint_pairs` at 32 VUs measured 22.45, 46.93 and 22.87 req/s;
`conditional-update` 25.68, 50.72 and 57.50. The ranges overlap on every workload. On the hot account the two are
indistinguishable at both concurrencies (16.9 vs 18.9 req/s median at 32 VUs). Three rounds on a shared laptop
cannot separate them, and Phase 1's "23.5% faster" was never a measurement of the strategies at all.

**Why an uncontended transfer takes hundreds of milliseconds: the machine is out of CPU, not the lock.**
- One sequential client (`baseline`, no contention at all) already sees a p50 of 63-99 ms across the six runs and
  a median p95 of 362-497 ms, and the service holds a database connection for 51-144 ms per transfer, while the transfer's SQL takes about 9 ms when
  measured on its own (`pg_stat_statements`, 20 sequential deposits).
- At 32 VUs on disjoint pairs (no shared rows) the connection is held 344-966 ms per transfer, but acquiring it takes
  only 5-43 ms: requests are not queueing for the pool, they are slow inside the transaction. The top statements
  of the `pessimistic` round 2 run add up to about 50 ms per transfer:

| calls | total ms | mean ms | statement |
| ---: | ---: | ---: | --- |
| 1,984 | 18,599.6 | 9.375 | `insert into postings (account_id,amount,created_at,currency,journal_entry_id,running_balance,id) values ($1,$2` |
| 992 | 6,886.2 | 6.942 | `insert into transfers (created_at,fx_quote_id,fx_rate,journal_entry_id,reference,source_account_id,source_amou` |
| 1,984 | 5,200.9 | 2.621 | `update accounts set balance=$1,holder_reference=$2,status=$3,updated_at=$4 where id=$5` |
| 992 | 5,128.9 | 5.170 | `insert into journal_entries (created_at,entry_type,fx_quote_id,fx_rate,reference,reverses_entry_id,id) values ` |
| 992 | 3,963.1 | 3.995 | `insert into idempotency_keys (created_at,expires_at,http_status,request_hash,response_body,status,idempotency_` |
| 2,976 | 3,501.7 | 1.177 | `SELECT COUNT(*) FROM postings WHERE journal_entry_id = target_entry_id` |
| 1,984 | 3,019.9 | 1.522 | `select ir1_0.idempotency_key,ir1_0.scope,ir1_0.created_at,ir1_0.expires_at,ir1_0.http_status,ir1_0.request_has` |
| 2,976 | 2,803.4 | 0.942 | `SELECT 1 FROM ONLY "public"."journal_entries" x WHERE "id" OPERATOR(pg_catalog.=) $1 FOR KEY SHARE OF x` |

  The rest of the ~444 ms hold time is spent between statements, in the JVM and waiting for a CPU.
- During those runs banking-service used 1.8-2.7 cores, PostgreSQL 1.3-1.7, the gateway 0.5-0.8, with k6 and the
  Windows host on the same 4 physical cores; the WSL load average reached 32 on 8 CPUs.
- A 90 s JFR recording of banking-service under a steady 8-VU load (`settings=profile`) shows no hot spot: the
  busiest Java method has 2.4% of the samples, the JVM uses about 35% of the machine, the machine total is 90%, and
  a young-generation GC of a 250 MB heap paused for 364 ms because its threads were waiting for CPU.

So the 300 ms "uncontended p50" in the Phase 1 report was CPU scheduling delay on an overloaded laptop. On the hot
account, by contrast, the row lock does serialise: connection usage is 1.3-2.3 s at 32 VUs against 0.34-0.97 s on
disjoint pairs. A side finding in the statements above: the deferred balance check runs once per posting and once
per entry (3 times for a 2-posting transfer, the `COUNT(*)` with 2,976 calls for 992 transfers). It costs about
4 ms per transfer here; it is left as is because it also guards postings added to an entry later.

## Decision (ADR 0005)
The numbers cannot pick a winner, so the decision rests on the FX-path argument: `pessimistic` locks every
participating row (2 for a transfer, 4 for an FX transfer through the `FX_POSITION` accounts, the payer row for a
hold capture) with one ordered `SELECT ... FOR UPDATE`, and reuses the same code for deposits, reversals and
captures. `conditional-update` exists only for same-currency transfers. `pessimistic` stays the default;
`conditional-update` stays selectable at startup for this benchmark. The bottleneck worth removing is the hot
*system* row that every deposit, FX transfer and capture shares; that is ADR 0010.
