# Memory: container peaks, limits, the GC choice and the observability stack (ADR 0029, ADR 0032)

## Method
`benchmarks/memory_peaks.py` samples every container's memory from `docker stats` (the cgroup's usage without its
inactive page cache, what the kernel compares with the limit) every 2 s, and each Spring JVM's heap, non-heap, threads
and GC pauses from its own `/actuator/prometheus` every third sample, for as long as a CI job or benchmark run lasts.
CI's "Default stack starts" job runs it through the smoke test, the benchmark workflow through every suite.

All runs on GitHub-hosted `ubuntu-24.04` runners, 4 vCPUs, Docker 28.0.4. MiB.

## Before limits
Run [44879949542](https://github.com/tsegale/cross-border-logistics-system/actions/runs/44879949542), AMD EPYC 7763,
15,983 MiB; transfers, hot-accounts, payment-flow and events suites; no limits, `MaxRAMPercentage=75`, G1 by ergonomics.

| Container | Peak | JVM heap used peak | Non-heap peak |
| --- | ---: | ---: | ---: |
| keycloak | 1,885 | | |
| logistics-service | 647 | 216 | 235 |
| banking-service | 609 | 178 | 209 |
| kafka | 488 | | |
| api-gateway | 398 | 142 | 110 |
| notification-service | 379 | 117 | 135 |
| banking-db | 312 | | |
| **sum of the 13 peaks** | **5,126** | | |

## With limits, Serial against G1
Same workload in both: payment flow at 8 and 16 VUs, then 2 bookings/s for 120 s with a 30 s broker outage. Runs
[44899628552](https://github.com/tsegale/cross-border-logistics-system/actions/runs/44899628552) (Serial, Intel Xeon
6975P-C, 15,701 MiB) and [44899641690](https://github.com/tsegale/cross-border-logistics-system/actions/runs/44899641690)
(G1, AMD EPYC 9V45, 15,983 MiB). **The two runs landed on different CPUs**, which GitHub chooses; latency differences
between them are partly the machine's.

| | Serial | G1 |
| --- | ---: | ---: |
| logistics peak (limit 512) | 490 | 505 |
| banking peak (limit 512) | 419 | 436 |
| notification peak (limit 352) | 310 | 295 |
| api-gateway peak (limit 320) | 277 | 270 |
| four services together | 1,496 | 1,506 |
| booking `POST` p50 / p95 / p99 ms, 8 VUs | 46 / 95 / 109 | 59 / 137 / 211 |
| booking `POST` p50 / p95 / p99 ms, 16 VUs | 40 / 117 / 144 | 84 / 172 / 231 |
| time to `BOOKED` p99 ms, 16 VUs | 977 | 1,156 |
| booking to email p50 / p95 / p99 ms | 361 / 617 / 661 | 374 / 613 / 666 |
| longest GC pause (banking, logistics) ms | 161, 181 (full collections) | 69, 58 |
| GC time over the run (banking, logistics) s | 0.3, 0.3 | 0.3, 0.4 |
| not booked | 0 | 0 |

## The observability stack against grafana/otel-lgtm
Run 44899628552 again: after the default-stack suites, the same events workload (2 bookings/s for 120 s, no outage)
once with the observability profile and once with `grafana/otel-lgtm:0.41.1` in its place
(`benchmarks/compose.lgtm.yml`, the services exporting traces, logs and metrics to it over OTLP).

| Observability profile | Peak | | otel-lgtm | Peak |
| --- | ---: | --- | --- | ---: |
| otel-collector | 85 | | lgtm (collector, Tempo, Loki, Prometheus, Grafana) | 623 |
| tempo | 99 | | | |
| loki | 63 | | | |
| prometheus | 77 | | | |
| alertmanager | 17 | | | |
| grafana | 241 | | | |
| **together** | **583** | | | **623** |

Booking to email in the two: p50 370 / 355 ms, p99 685 / 682 ms; the services' telemetry did not slow the flow.

## Reading
- Limits cut Keycloak from 1,885 to 572 MiB and the stack's sum of peaks from 5,126 to about 2,840 MiB, with the same
  work done: without a limit the JVMs grew their heaps toward 75 % of the host before collecting.
- Under limits Serial and G1 use the same memory, within 20 MiB per service. Serial's percentiles were better on its
  runner, G1's worst pauses shorter (Serial ran two full collections of 161 and 181 ms). Serial is kept (ADR 0029): the
  measured p99 of a booking stayed under 150 ms with it, and a full collection of a heap this small is short. The
  comparison should be repeated on one runner type before a production heap is sized.
- logistics reached 96 % of its 512 MiB under the payment flow: its limit is now 576 MiB with the heap at 45 %.
- The separate observability stack uses about the same memory as the all-in-one image (583 against 623 MiB) and has
  what the image lacks (ADR 0032).

## On the laptop
Intel Core i5-1135G7 (4 cores, 8 threads), 7.7 GiB, Docker Desktop 29.7.2 with a 3,784 MiB VM: the default stack under
its limits, after the seed and the smoke test (91 calls, passed on the second attempt; the first timed out on a cold
gateway).

| Container | Used | Limit |
| --- | ---: | ---: |
| keycloak | 556 | 704 |
| logistics-service | 462 | 576 |
| banking-service | 413 | 512 |
| kafka | 337 | 512 |
| notification-service | 327 | 352 (now 384) |
| api-gateway | 247 | 320 |
| banking-db | 116 | 192 |
| logistics-db | 85 | 192 |
| notification-db | 45 | 128 |
| mailpit | 32 | 48 |
| keycloak-db | 31 | 128 |
| redis | 15 | 32 |
| **together** | **2,665** | |
