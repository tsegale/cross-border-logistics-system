# 33. SLOs on what customers feel, multi-window burn-rate alerts, and a fire drill

Date: 2026-11-21 · Status: Accepted · Builds on ADR 0031, ADR 0032

## Context
docs/operations.md listed signals and thresholds per service. A threshold on a rate ("more than 1 % errors over an
hour") either pages for a two-minute blip or stays quiet through a slow leak that costs more over a month. Nobody had
written down what "working" means for a customer, or how much failure is acceptable.

## Decision
**Four objectives over 28 days, measured at the service** (docs/slo.md): bookings and transfers succeed (non-5xx,
99.5 %), bookings answer within 500 ms (95 %), and emails reach the mail server within 60 s of the booking's commit
(99 %). The numbers are the hosted-runner benchmarks with room for a slower machine and restarts; docs/slo.md gives each
one's reason. Booking to email is measured end to end from the event's UUIDv7, whose first 48 bits are the moment the
producer wrote it, to the mail server's acceptance, so outbox, Kafka, consumer and sender delays all count.

**Ledger drift is not an objective but a hard alert**: any discrepancy pages at once, from a reconciliation every 5
minutes, and a reconciliation that stops running is alerted too.

**Burn-rate alerts by the Google SRE workbook's method**: two pairs of windows that page (14.4x over 1 h and 5 m, 6x over
6 h and 30 m) and two that open a ticket (3x over 1 d and 2 h, 1x over 3 d and 6 h). The long window says the budget is
going, the short window says it still is, so a page stops soon after the cause does. The rules are generated from the
objectives by `infra/observability/rules/generate_slo_rules.py`, so the four SLOs cannot drift apart, and CI fails if
the committed file differs from the generator's output.

**Symptom alerts** for what needs a person whatever the budget says: parked payment commands, UNKNOWN and failing
emails, dead letters, a stuck outbox, consumer lag, approvals about to expire, ledger drift, a service down, upstream
errors at the gateway, a container near its memory limit.

**Prometheus alerting rules and Alertmanager, not Grafana-managed alerts.** Rules are YAML files that `promtool test
rules` exercises with synthetic series: each alert stays quiet on healthy input and fires with its exact labels and
annotations after its `for`. Grafana's alerting stores rules in its database (provisionable, but testable only against
a running Grafana). Alertmanager emails the on-call address through Mailpit locally; a page repeats hourly, a ticket
every 12 hours, and `ServiceDown` inhibits the rest of that service's alerts.

**Every alert has a runbook** (docs/runbooks/) linked from its `runbook_url`: what it means, which dashboard and query,
how to fix or escalate.

**A fire drill** (`scripts/alert_fire_drill.py`, a manual workflow) causes every alert once against the running stack
and records when each fired and resolved. Two are simulated in the database and say so (a parked command, an UNKNOWN
email); their fixes are the real operator actions.

## Consequences
- **The SLIs see only what reaches a service.** A booking the gateway refuses because logistics is down never reaches
  logistics' metrics; `ServiceDown` and `GatewayUpstreamErrors` cover that gap, not the budget.
- **28-day windows need 28 days of data** for the slow-burn ticket alerts to mean what they say. On a stack started an
  hour ago, every window holds the same hour, so a short burn fires the slow alerts too, which the fire drill shows.
- **Slow-burn alerts stay firing for hours after a burn**, by design; the drill cannot wait for them to resolve, so their
  recovery is covered by the promtool tests only.
- Thresholds are starting points from benchmarks, not from production traffic. The first month of real traffic should
  revisit them.
