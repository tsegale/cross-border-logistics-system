# 29. Memory budgets per container, and JVM sizing from each limit

Date: 2026-11-21 · Status: Accepted

## Context
No container had a memory limit, and every JVM ran with `-XX:MaxRAMPercentage=75`. Without a container limit the JVM
takes 75 % of the whole Docker VM as its maximum heap, and G1 grows the heap lazily toward it before it collects.
Measured on the hosted runner without limits (run 44879949542, AMD EPYC 7763, 4 vCPUs, 15,983 MiB, every benchmark
suite but the cdc and system-balance ones): Keycloak peaked at **1,885 MiB**, logistics at 647, banking at 609, and the
twelve containers' peaks added up to **5,126 MiB**, although the four services' heaps held only 117 to 216 MiB of live
data at their peaks. On the 3.7 GiB Docker Desktop VM of the development laptop the stack froze the VM: Phase 4's
local smoke test never finished.

## Decision
**Every container has a `mem_limit`**, sized from the peaks `benchmarks/memory_peaks.py` measured under the smoke test
and the benchmarks, with headroom; **every JVM sizes its heap from its own limit**.

| Container | Limit | Peak, no limit (benchmarks) | Peak, with limits (smoke test) | Peak, with limits (benchmarks) | JVM / process sizing |
| --- | ---: | ---: | ---: | ---: | --- |
| keycloak | 704m | 1,885 | 574 of 640 (first limit) | 572 | heap 50 % (`JAVA_OPTS_KC_HEAP`), kc.sh's G1 and metaspace cap |
| logistics-service | 576m | 647 | 418 | 490 of 512 (first limit) | heap 45 %, Serial GC, metaspace 192m, 512k stacks |
| banking-service | 512m | 609 | 377 | 419 | heap 45 %, Serial GC, metaspace 192m, 512k stacks |
| kafka | 640m | 488 | 421 of 448 (first limit) | 415 | `-Xms256m -Xmx256m`, Serial GC, a 16 MiB cleaner dedupe buffer. 512m until the rotation fix: a certificate reload runs `kafka-configs` in the broker's cgroup, and `memory.peak` reached the 512 MiB limit with 23 MiB swapped on the laptop; CI's broker went silent in that reload. At 640m the reload peaks at 502 MiB, steady state 391 to 415 MiB, measured on the laptop (Docker Desktop, 8 CPUs, 3.7 GiB VM) |
| notification-service | 384m | 379 | 250 | 310 (327 of 352 on the laptop) | heap 45 %, Serial GC |
| api-gateway | 320m | 398 | 226 | 277 | heap 45 %, Serial GC |
| banking-db | 192m | 312 | 85 | 108 | Postgres defaults (128 MB shared buffers) |
| logistics-db | 192m | 128 | 84 | 124 | |
| notification-db | 128m | 61 | 56 | 62 | |
| keycloak-db | 128m | 54 | 54 | 58 | |
| mailpit | 48m | 18 | 10 | 19 | |
| redis | 32m | 8 | 4 | 8 | |
| partner-simulator | 48m | | | | Python, stdlib only; 14.7 MiB after the laptop's cold start and smoke test (ADR 0034) |
| kafka-init | 256m | 137 | (exits first) | 84 | `KAFKA_HEAP_OPTS=-Xmx128m` |
| ca | 96m | | | | step-ca (ADR 0039). 64m until the Kafka TLS commit: `memory.peak` 67.2 MiB on the laptop, the 54 MB binary's pages counted against the limit |
| cert-agent | 48m | | | | step CLI and sh (ADR 0039). 32m until the Kafka TLS commit: `memory.peak` 35.2 MiB renewing all 14 certificates on the laptop; at 32m the 44 MB `step` binary was paged out between calls and each certificate took 48 s |
| objectstore | 512m | | | | SeaweedFS 4.55, master, volume server, filer and S3 gateway in one process (ADR 0046). At its first limit, 256m, it hit the limit 15,405 times during banking's first full backup, with 98 MiB anonymous resident and 256 MiB swapped: about 350 MiB in use, measured on the laptop |
| debug-ports (profile debug) | 32m | | | | socat |
| docker-api (profile observability) | 32m | | | | HAProxy, the read-only Docker API for the collector (ADR 0041) |

MiB throughout; smoke-test peaks from CI run 44887512287, benchmark peaks (payment flow at 16 VUs, the events suite
with a broker outage) from run 44899628552 (Intel Xeon 6975P-C, 4 vCPUs); docs/benchmarks/memory.md. **The running default stack's limits
add up to 3,920 MiB** (3,776 before the CA and its agent, 3,728 before the partner simulator), 136 MiB past the 3,784 MiB
of a 3.7 GiB VM: limits are ceilings, not reservations, and no two containers peak at once; its measured peaks add up to 2,558 MiB under the smoke
test and about 2,840 MiB under the benchmarks.

**On the laptop** (Intel Core i5-1135G7, 8 threads, 7.7 GiB; Docker Desktop's VM 3,784 MiB) the default stack
starts within these limits and the smoke test passes, 91 calls; the containers held 2,665 MiB together after it. A cold
start there is slow: logistics' warm-up ran into its one-minute limit before Keycloak answered tokens, its healthcheck
gave up, and `docker compose up -d --wait` had to be run a second time; the first seed and smoke runs timed out on the
gateway's first requests, and passed when repeated. notification-service sat at 327 MiB of its first 352, so its
limit is 384. Since the warm-up waits for its dependencies on a deadline of its own and kafka-init retries
each admin call (2026-11-24), one `docker compose up -d --wait` from stopped containers brings the whole default
stack up healthy: 283 s on the same laptop, exit 0, logistics' warm-up 17 steps with none failed after it had waited
for Keycloak and banking; the smoke test then passed, 91 calls.

**JVM flags** (`JAVA_OPTS` per service in docker-compose.yml, the Dockerfiles keep a default for a bare `docker run`):
`-XX:MaxRAMPercentage=<45|50> -XX:InitialRAMPercentage=20 -XX:MaxMetaspaceSize=192m -Xss512k
-XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC`. Heap is under half the limit because non-heap is large in these
services: metaspace 67 to 139 MiB, non-heap in all 97 to 194 MiB at the smoke test's peak. Metaspace is capped above
its measured peak, so a class-loading leak fails with an `OutOfMemoryError`, which `ExitOnOutOfMemoryError` turns into a
restart, instead of growing into the limit. Thread stacks at 512 KiB: banking peaked at 124 threads under load.

**Serial GC**, chosen by measurement (the GC comparison below), and made explicit: under a 1,792 MiB limit HotSpot's
ergonomics already pick Serial, but by accident of the limit; an explicit flag keeps the choice when a limit changes.
`JAVA_GC=-XX:+UseG1GC` switches every service for the benchmark workflow's comparison.

**Profiles and the VM sizes they fit**

| Profile | Limits | Fits |
| --- | ---: | --- |
| default | 3,728 MiB | 4 GiB VM (the 3.7 GiB Docker Desktop default), with the observability stack off |
| default + debug | 3,760 MiB | 4 GiB |
| default + observability | 5,264 MiB | 8 GiB |
| default + observability + benchmarks (cdc: Debezium about 270 MiB, ADR 0025) | about 5,500 MiB | 8 GiB |
| everything, with headroom for builds | | 16 GiB |

## Measured: Serial against G1
The benchmark workflow with `java_gc` serial and g1, same workload, both under these limits (docs/benchmarks/memory.md):

| | Serial (run 44899628552, Xeon 6975P-C) | G1 (run 44899641690, EPYC 9V45) |
| --- | ---: | ---: |
| four services' peaks together | 1,496 MiB | 1,506 MiB |
| booking p95 / p99, 16 VUs | 117 / 144 ms | 172 / 231 ms |
| booking to email p99 | 661 ms | 666 ms |
| longest pause (banking, logistics) | 161, 181 ms (full collections) | 69, 58 ms |

Memory is the same under a limit; that was Smart Tours' reason for Serial (ADR 0018 there), and it does not hold here.
Serial's request percentiles were better, but on a different CPU, and G1's worst pauses were shorter. **Serial stays**:
equal memory, booking p99 under 150 ms, and one GC thread instead of G1's concurrent ones on a host that runs fourteen
containers on four cores. The runs landed on different CPUs, so the latency difference is not established; the
comparison is to be repeated on one runner type before a production heap is sized, where G1 is the usual choice.

## Consequences
- **A JVM that needs more memory now dies instead of slowing the whole VM down**, and compose restarts nothing on its
  own: `ServiceDown` and `ContainerMemoryNearLimit` (ADR 0033) say which and why.
- A new container needs a limit and a row here; the memory sampler in CI shows its peak against it.
- Kafka's and Postgres' usage includes active page cache, which the kernel reclaims under the limit before it kills
  anything; their 90 % alert line can be reached by cache alone.
- The smoke-test peaks above predate tracing (ADR 0030); the benchmark runs below include it, with export off in the
  default stack. The observability profile's own memory is in ADR 0032.
