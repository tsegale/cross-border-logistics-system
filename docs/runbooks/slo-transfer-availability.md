# Slo: transfer availability

Alerts: TransferAvailabilityBudgetBurnFast, TransferAvailabilityBudgetBurnSlow

**What it means.** `POST /api/v1/transfers` fails with server errors faster than the 0.5 % budget allows (docs/slo.md).
Customers cannot move money.

**Check.**
1. [RED: banking-service](http://127.0.0.1:3000/d/red-banking-service), the errors panel, then an exemplar's trace.
2. `{service_name="banking-service"} | detected_level="error"` in Loki.
3. A lock timeout under contention (ADR 0005) shows as `503` with `lock-timeout`: compare with the request rate. A
   database outage as `database-unavailable`.

**Fix.** Restore the database; if lock timeouts follow a hot account, that is ADR 0010's case (system accounts post
unlocked). Never "fix" a transfer by editing balances: the ledger is append-only (ADR 0004). **Escalate** to the banking
owner, and check [ledger-drift](ledger-drift.md) has not fired too.
