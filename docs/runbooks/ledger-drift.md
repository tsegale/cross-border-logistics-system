# Ledger drift

Alerts: LedgerDrift, ReconciliationNotRunning

**What it means.** The scheduled reconciliation (every 5 minutes, `ReconciliationMonitor`) found an account whose stored
balance differs from the sum of its postings, or a currency whose postings do not sum to zero. That must never happen
(ADR 0004): it is not an objective but an invariant. `ReconciliationNotRunning`: no check for 15 minutes, so drift
could go unseen.

**Check.**
1. [Money](http://127.0.0.1:3000/d/money): "Reconciliation drift" and "Last reconciliation".
2. The report itself, as an operator or auditor: `GET /api/v1/ledger/reconciliation` lists each discrepancy with its
   account and the difference.
3. `{service_name="banking-service"} |= "reconciliation"` for when it began; the account's postings around then
   (`GET /api/v1/accounts/{id}/postings`), each journal entry (`GET /api/v1/journal-entries/{id}`) and its trace
   (`{ span.freightflow.journal_entry.id = "<id>" }` in Tempo).

**Fix.** Do not edit balances or postings by hand. Stop money movement if the drift grows (scale banking to zero or
close the money routes at the gateway). Find the entry that broke the invariant; correct it with a reversing entry
through four-eyes approval (ADR 0021). **Escalate at once** to the banking owner and finance: this is an incident
whatever its size.
