# 32. The observability stack: separate Grafana, Prometheus, Tempo, Loki and Alertmanager behind a profile

Date: 2026-11-21 · Status: Accepted · Builds on ADR 0029, ADR 0031

## Context
Traces, logs and metrics need somewhere to go, and a person needs one place to look. Smart Tours used
`grafana/otel-lgtm`, one container with a collector, Tempo, Loki, Prometheus and Grafana, which its authors describe as
for development and demos. This system adds Kafka, money and alerts that must page, and the default stack already uses
most of a 3.7 GiB Docker VM (ADR 0029).

## Decision
**Separate containers, each pinned, behind `--profile observability`** (docker-compose.yml, infra/observability/):

| Component | Image | Limit | Role |
| --- | --- | ---: | --- |
| Collector | `otel/opentelemetry-collector-contrib:0.165.0` | 192m | OTLP in, tail sampling, redaction, infrastructure receivers (ADR 0031) |
| Tempo | `grafana/tempo:3.1.1` | 384m | traces, monolithic, local storage; span metrics and the service graph to Prometheus |
| Loki | `grafana/loki:3.7.10` | 256m | logs over OTLP, filesystem storage, 7 days |
| Prometheus | `prom/prometheus:v3.16.0` | 384m | scrapes the services (exemplars), receives remote write, evaluates the rules |
| Alertmanager | `prom/alertmanager:v0.35.0` | 64m | routes alerts as emails (ADR 0033) |
| Grafana | `grafana/grafana:13.3.1` | 384m | dashboards and data sources provisioned from files, read-only. 256m until Phase 7: 13.3.1 peaked at 255.9 MiB, its whole limit, in CI run 45607746751 and was OOM-killed at start in run 45609180797 (exit 137) |

Why not `grafana/otel-lgtm`:
- **No Alertmanager.** Alerts would be Grafana-managed rules, which promtool cannot test (ADR 0033).
- **One version for five components.** A Tempo or Loki fix waits for a new bundle; here each is a Dependabot bump of
  its own.
- **Its collector is the bundle's**, configured inside the image; tail sampling, redaction and the infrastructure
  receivers would mean replacing its configuration file anyway.
- **Production does not run it.** Separate containers map one to one onto separate deployments (or Grafana Cloud's
  hosted equivalents); the bundle maps onto nothing.

What it would save, memory, is measured below on the same runner and workload.

**Pyroscope (continuous profiling) is left out.** It would be the seventh container and a Java agent or the
`async-profiler` integration in every JVM; on this stack the measured questions (where a booking spends its time,
which hop is slow) are answered by traces and exemplars. JFR on demand stays available
(`BANKING_JAVA_TOOL_OPTIONS`, benchmarks/compose.bench.yml) for the rare CPU question.

**Grafana** is provisioned: data sources linked to each other (an exemplar opens its trace, a span its logs, a log line's
`trace_id` its trace, Tempo's service map from its span metrics), and eight dashboards generated from
`infra/observability/grafana/build_dashboards.py` (one RED dashboard per service, booking to email, money, Kafka,
runtime). Every panel links to the matching traces or logs; `scripts/check_dashboards.py` fails CI otherwise. Anonymous
viewers who may explore, and nothing they change is saved: a change to a dashboard is a commit.

## Measured
Run 44899628552 (Intel Xeon 6975P-C, 4 vCPUs), 2 bookings/s for 120 s with the services exporting everything
(docs/benchmarks/memory.md): the six containers peaked at **583 MiB** together (collector 85, Tempo 99, Loki 63,
Prometheus 77, Alertmanager 17, Grafana 241), `grafana/otel-lgtm` at **623 MiB** under the same workload, without
Alertmanager. Memory does not decide it; what the bundle lacks does. Booking to email was the same with either (p99 685
and 682 ms) and as without them (661 ms).

## Consequences
- **Default plus observability does not fit a 3.7 GiB VM** (ADR 0029's profile table): it needs 8 GiB. CI runs it on
  every push.
- Six more images to keep current; Dependabot covers compose images.
- Ports for Grafana, Prometheus, Tempo, Loki and Alertmanager are published on 127.0.0.1 with the profile, for a
  developer and the CI checks. Nothing in them is authenticated; in production they sit behind the same identity
  provider as everything else.
