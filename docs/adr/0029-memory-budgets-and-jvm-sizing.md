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

| Container | Limit | Peak, no limit (benchmarks) | Peak, with limits (smoke test) | JVM / process sizing |
| --- | ---: | ---: | ---: | --- |
| keycloak | 704m | 1,885 | 574 of 640 (first limit) | heap 50 % (`JAVA_OPTS_KC_HEAP`), kc.sh's G1 and metaspace cap |
| logistics-service | 512m | 647 | 418 | heap 50 %, Serial GC, metaspace 192m, 512k stacks |
| banking-service | 512m | 609 | 377 | heap 45 %, Serial GC, metaspace 192m, 512k stacks |
| kafka | 512m | 488 | 421 of 448 (first limit) | `-Xms256m -Xmx256m` |
| notification-service | 352m | 379 | 250 | heap 45 %, Serial GC |
| api-gateway | 320m | 398 | 226 | heap 45 %, Serial GC |
| banking-db | 256m | 312 | 85 | Postgres defaults (128 MB shared buffers) |
| logistics-db | 192m | 128 | 84 | |
| notification-db | 128m | 61 | 56 | |
| keycloak-db | 128m | 54 | 54 | |
| mailpit | 48m | 18 | 10 | |
| redis | 32m | 8 | 4 | |
| kafka-init | 256m | 137 | (exits first) | `KAFKA_HEAP_OPTS=-Xmx128m` |
| debug-ports (profile debug) | 32m | | | socat |

MiB throughout; smoke-test peaks from CI run 44887512287 (hosted runner, 4 vCPUs). **The running default stack's limits
add up to 3,696 MiB**, inside the 3,784 MiB of a 3.7 GiB VM, and its measured peaks to 2,558 MiB.

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
| default | 3,696 MiB | 4 GiB VM (the 3.7 GiB Docker Desktop default), with the observability stack off |
| default + debug | 3,728 MiB | 4 GiB |
| default + observability | 5,232 MiB | 8 GiB |
| default + observability + benchmarks (cdc: Debezium about 270 MiB, ADR 0025) | about 5,500 MiB | 8 GiB |
| everything, with headroom for builds | | 16 GiB |

## Measured: Serial against G1
(filled in from the benchmark workflow's runs with `java_gc` serial and g1; docs/benchmarks/memory.md)

## Consequences
- **A JVM that needs more memory now dies instead of slowing the whole VM down**, and compose restarts nothing on its
  own: `ServiceDown` and `ContainerMemoryNearLimit` (ADR 0033) say which and why.
- A new container needs a limit and a row here; the memory sampler in CI shows its peak against it.
- Kafka's and Postgres' usage includes active page cache, which the kernel reclaims under the limit before it kills
  anything; their 90 % alert line can be reached by cache alone.
- The smoke-test peaks above predate tracing (ADR 0030); the benchmark runs below include it, with export off in the
  default stack. The observability profile's own memory is in ADR 0032.
