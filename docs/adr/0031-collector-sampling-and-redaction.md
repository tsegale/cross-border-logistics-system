# 31. An OpenTelemetry Collector in front of the backends: tail sampling, redaction, infrastructure metrics

Date: 2026-11-21 · Status: Accepted · Builds on ADR 0030

## Context
The services trace every request (ADR 0030). Kept whole, that is every booking's 19 spans (CI's trace check), which is right for
dev and CI and too much to store in production. Which traces matter is only known at the end: an error deep in
notification, a booking that took eight seconds, a hold that moved money. A head sampler in each service decides at the
first span and cannot know any of that.

Some facts no service can report about itself: Postgres' connections and sizes, Kafka consumer lag per group, Redis,
and each container's memory against its limit (ADR 0029).

And telemetry is a channel personal data can leave by (docs/threat-model.md): a careless log line, an exception
message quoting a request.

## Decision
**The services send traces and logs only to an OpenTelemetry Collector** (`otel/opentelemetry-collector-contrib:0.165.0`,
`infra/observability/otel-collector.yaml`), never to Tempo or Loki. A backend can change without touching a service.

**Pipelines**

    traces: otlp -> memory_limiter -> redaction -> tail_sampling -> batch -> Tempo
    logs:   otlp -> memory_limiter -> redaction -> transform (log bodies) -> batch -> Loki
    metrics: postgresql x3, kafka_metrics, redis, docker_stats -> memory_limiter -> batch -> Prometheus (remote write)

- **memory_limiter first** (150 MiB of a 192 MiB container, `GOMEMLIMIT` 150 MiB): under pressure the collector refuses
  data (the services' exporters retry and then drop) instead of being OOM-killed.
- **Tail sampling**, decided 10 s after a trace's first span. Kept: every trace with an error span, every trace longer
  than 2 s, every trace with `freightflow.money.moved=true` (a journal entry was posted), and `TAIL_SAMPLING_PERCENTAGE`
  of the rest. **100 in dev and CI** (infra/observability/observability.env): everything is kept, which the CI trace
  check and the fire drill rely on. **10 in production.** All spans of a trace must reach the same collector instance;
  one instance here, several would put the `loadbalancing` exporter, keyed by trace id, in front.
- **Redaction**, the second line behind the services' own rule and test (ADR 0030): any attribute value that looks like
  an email address, a bearer token or a JWT is masked, and any key containing `authorization` or `password`, whatever
  its value; the `transform` processor applies the email and token patterns to log bodies, which the redaction
  processor does not read.
- **Infrastructure metrics** from receivers: Postgres per database as each service's own user (no superuser), Kafka
  consumer lag as a describe-only `monitoring` SCRAM user (ADR 0022: Describe on the cluster, topics and groups, no
  Read, no Write), Redis, and `docker_stats` for each container's memory and limit.

**The services' own metrics are scraped by Prometheus**, not pushed through the collector. `/actuator/prometheus`
already exists on every service; scraped as OpenMetrics, its latency histograms carry **exemplars**, a trace id on a
bucket, which a Grafana panel turns into a link to that request's trace. Pushing the same metrics over OTLP would be a
second path to the same numbers. Business metrics are gauges read from the tables (outbox age, approvals, parked
commands, shipments per payment state, emails by status) or counters incremented after commit, so every instance
agrees and a rollback counts nothing. A table gauge waits at most 250 ms for its query and gives its last value otherwise
(`BoundedGauge`): the fire drill showed that with a database down, gauges that queried it on every scrape made the
whole scrape time out, and the request metrics of that very outage went missing with it.

## Measured
The collector peaked at **85 MiB** of its 192 MiB limit keeping every trace, with 2 bookings/s for 120 s (19
spans a booking in CI's trace check) and its five infrastructure receivers scraping every 15 s (run 44899628552, Intel Xeon 6975P-C;
docs/benchmarks/memory.md). Production's 10 % keeps fewer spans past the decision but holds the same 10 s of them before
it.

## Consequences
- **An exemplar can point at a trace tail sampling dropped** in production (one of the 90 % of healthy, fast traces).
  The trace link then finds nothing; the slow and failed requests a person clicks on are the ones always kept.
- **A 10 s decision wait** holds every trace's spans in memory for 10 s (`num_traces` 20,000), and a trace whose last
  span arrives later (an email sent after a mail server outage) is decided without it: the late spans form a second,
  partial trace. The 2 s latency policy still keeps the first part.
- **The collector runs as root for the Docker socket**, mounted read-only, for container memory. Read-only still lets
  it list and inspect every container on the host; in Kubernetes the kubelet's cAdvisor endpoint replaces it.
  (Superseded in Phase 7: a read-only mount does not limit the API, so the collector now runs as its image's user and
  reads the Docker API through `docker-api`, an HAProxy allowlist of five GET paths. ADR 0041.)
- The monitoring user's password is one more secret (`secrets/kafka_monitoring_password`, generated with the others).
