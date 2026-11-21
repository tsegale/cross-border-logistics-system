# Dead letters

Alerts: DeadLettersWaiting

**What it means.** Events that failed every retry, or could not be read, are stored as dead letters and wait for an
operator (ADR 0026).

**Check.** `GET /api/v1/dead-letters?status=NEW` (notification) or `GET /api/v1/logistics/dead-letters?status=NEW`
(logistics) as an operator or auditor: each with its error. The event's trace, from `traceparent` in its stored
headers, shows where it failed.

**Fix.** Fix the cause (a missing shipment projection, a bug), then `POST .../dead-letters/{id}/redrive`; the handler
runs once and records who redrove it. A poison record that cannot be read stays: note it in the incident and leave it.
**Escalate** to the consuming service's owner.
