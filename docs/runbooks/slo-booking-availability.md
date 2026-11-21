# Slo: booking availability

Alerts: BookingAvailabilityBudgetBurnFast, BookingAvailabilityBudgetBurnSlow

**What it means.** `POST /api/v1/shipments` answers with server errors (5xx) fast enough to use up the 0.5 % error
budget of 28 days early (docs/slo.md). Fast: 2 % of the budget in an hour (pages). Slow: 10 % in a day (ticket).
Customers cannot book.

**Check.**
1. Dashboard [RED: logistics-service](http://127.0.0.1:3000/d/red-logistics-service): which status, since when; open an exemplar on the
   latency panel for one failing request's trace.
2. The errors by type, in Loki:
   `{service_name="logistics-service"} | detected_level="error"`
3. `sum by (status) (rate(http_server_requests_seconds_count{service="logistics-service",uri="/api/v1/shipments",outcome="SERVER_ERROR"}[5m]))`
   - **503 `database-unavailable`**: logistics' Postgres. `docker compose ps logistics-db`, its logs, its memory
     against its limit on [Runtime](http://127.0.0.1:3000/d/runtime).
   - **500 `internal-error`**: a bug. The trace's error span names the exception; the log line has its stack.
   - **503 `issuer-unavailable`**: Keycloak; see [service-down](service-down.md).

**Fix.** Restore the dependency; a 503 is retried by clients with the same `Idempotency-Key` and books once (ADR 0006).
Roll back a release that introduced 500s. **Escalate** to the logistics owner if neither explains it within 15 minutes.
