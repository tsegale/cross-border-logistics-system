# Invariant failure: a rule no correct run breaks

Alerts: InvariantViolated, InvariantAuditNotRunning

**What it means.** banking and logistics each audit, daily and on demand, rules the schema cannot enforce on its own
(ADR 0044). `invariant` names the rule:

| Service | Invariant | Broken when |
| --- | --- | --- |
| banking | `journal-entries-balance` | an entry's postings do not sum to zero in a currency |
| banking | `balances-reconcile` | a stored balance, shard or checkpoint differs from its postings, or a currency's postings do not sum to zero (the reconciliation; see [ledger-drift](ledger-drift.md)) |
| banking | `holds-captured-once` | a capture entry belongs to no hold or to two, a hold is captured beyond its amount, or the payer was debited other than what the hold says |
| banking | `customs-disbursements` | CUSTOMS_DISBURSEMENTS in a currency differs from the duties captured minus the estimates settled |
| banking, logistics | `outbox-in-order` | an aggregate's retained events have a gap, were published out of sequence, or one has waited unpublished for over 15 minutes |
| logistics | `shipments-paid-once` | a delivered, paid-for shipment has no confirmed freight capture (past 15 minutes) or more than one, or a duty hold's capture disagrees with the shipment |

- **InvariantViolated (page)**: money or events are in a state that no correct run produces.
- **InvariantAuditNotRunning (ticket)**: an audit is half an interval overdue. It failed, or was switched off with
  `FREIGHTFLOW_INVARIANTS_ENABLED`.

**Check.**
- The report, with the offending rows (the first five, and how many there are):
  `GET /api/v1/ledger/invariants` and `GET /api/v1/logistics/invariants`, as an operator or auditor.
- Across both services and the Kafka topics: `python scripts/audit_invariants.py`. It also compares the captures each
  service recorded per currency, and each topic's sequence per aggregate.
- The logs: `Invariant audit: [...] broken`, and whatever the writer logged at the time of the rows named.

**Fix.**
1. **Stop the writer first.** For a ledger invariant, pause transfers and captures at the gateway, or scale the
   service to zero, before anything is repaired. A second bad write makes the first harder to explain.
2. **Find the change that wrote the row**, from the example's ids:
   - the journal entry's actor and request id;
   - the trace of that request (`TraceContext`);
   - the event's envelope.
3. **Repair forward, never in place.** The ledger is append-only (ADR 0004):
   - a wrong entry is reversed by a REVERSAL through the four-eyes approvals (ADR 0021);
   - an unpublished outbox row is published by its relay once the broker is back ([outbox-stuck](outbox-stuck.md));
   - a missing capture is retried from its parked payment command
     ([payment-commands-parked](payment-commands-parked.md)).
4. **Audit again**: the gauge clears on the next audit that holds.
5. **A row that only an edit outside the services could have written** means someone has database access they
   should not. Treat it as a security incident: rotate the owner password and review who holds it (ADR 0041).

After a restore, run the script before the services take traffic: [restore](restore.md).
