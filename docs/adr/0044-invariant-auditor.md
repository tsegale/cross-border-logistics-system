# 44. The invariant auditor

Date: 2026-11-26 · Status: Accepted · Builds on ADR 0004 (ledger), ADR 0010 (system account shards), ADR 0025
(outbox), ADR 0036 (customs disbursements)

## Context
The database enforces what a row can say. A posting cannot be updated, a hold cannot be captured beyond its amount,
an entry needs an actor. Some rules span rows, tables or services, and only a check over what is stored can see them:
- an entry's postings sum to zero in each currency;
- a capture belongs to exactly one hold;
- the customs liability equals what was collected minus what was paid;
- an event was published once and in order;
- a delivered shipment was paid exactly once, across logistics and banking.

The reconciliation (ADR 0004) covered balances only. The Phase 7 brief asks for an auditor that runs at any time and
after every chaos experiment and restore, as a scheduled job with a metric and an alert, and as a script.

## Decision
- **Each service audits its own database**, daily and on demand. The audit runs in one repeatable-read transaction,
  so every check sees the same instant, and writes nothing.
- **Banking's invariants** (`GET /api/v1/ledger/invariants`, operators and auditors):
  - `journal-entries-balance`: every entry's postings sum to zero in each currency;
  - `balances-reconcile`: every stored balance, shard and checkpoint matches its postings (the reconciliation);
  - `holds-captured-once`: a capture entry belongs to exactly one captured hold, never beyond its amount, and
    debits the payer exactly what the hold says it captured;
  - `customs-disbursements`: per currency, CUSTOMS_DISBURSEMENTS holds the duties captured minus the estimates
    settled;
  - `outbox-in-order`: an aggregate's retained events are a gapless run ending at its last sequence, were published
    in sequence order, and none has waited past 15 minutes.
- **Logistics' invariants** (`GET /api/v1/logistics/invariants`):
  - `shipments-paid-once`: a delivered shipment that is paid for has exactly one freight capture banking confirmed
    (or one still pending within 15 minutes), and its duty hold's capture agrees with the shipment;
  - `outbox-in-order`, as banking's.
- **Each report also carries the captures per currency, count and amount.** Neither service may read the other's
  database, so `scripts/audit_invariants.py` compares them. It retries for a minute while captures in flight settle.
- **The script also reads every topic from the beginning**, inside the broker's container as its admin. Per
  aggregate, the first copy of each sequence must arrive in order without a gap. Two events with one sequence break
  the rule. A second copy of one eventId is a redelivery, which consumers drop (ADR 0026); it is counted, not failed.
- **Metrics and alerts:**
  - `freightflow_invariants_violations{service,invariant}` counts the rows that break each invariant at the last
    audit, at zero from the start. `InvariantViolated` pages on any.
  - `freightflow_invariants_audit_age_seconds` and `_interval_seconds` drive `InvariantAuditNotRunning`, a ticket
    when an audit is half an interval overdue.
  - Both alerts have promtool tests, a runbook (`docs/runbooks/invariant-failure.md`) and fire-drill triggers.
- **Tests plant each corruption** as the database owner with triggers off, as only a bug, a restore or a manual edit
  could, and expect it named. Banking's test does this on a database of its own. Logistics' looks for its own
  shipments, since its database is shared with other tests.
- **CI runs the script** after the smoke test and the certificate rotation, and every chaos experiment and restore
  drill ends with it.

## Consequences
- **A daily audit finds a break up to a day late.** The schedule is a setting; experiments and drills call the audit
  directly.
- **Cost grows with the tables.** The audits are full scans of postings, holds and the retained outbox, cheap at this
  size. A large ledger would audit per period, from a checkpoint, as the reconciliation's checkpoints do (ADR 0010).
- **The cross-service check compares totals, not ids.** A capture missing on one side and an extra one on the other,
  in the same currency and amount, would cancel out. Ids would need a list exchanged between services: a feed of
  capture ids is the next step if that matters.
- **The topic check reads everything**, which is fine for a drill and too slow for a large cluster. There it would
  run as a consumer group of its own, checkpointing per partition.
