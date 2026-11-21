# Slo: booking latency

Alerts: BookingLatencyBudgetBurnFast, BookingLatencyBudgetBurnSlow

**What it means.** More than 5 % of bookings take longer than 500 ms in logistics (docs/slo.md), fast enough to use the
budget early. Measured on the hosted runner, p95 is 42 to 50 ms (docs/benchmarks/payment-flow.md): a burn means
something is 10 times slower than normal.

**Check.**
1. [Booking to email](http://127.0.0.1:3000/d/booking-to-email), "Booking latency p95": click an exemplar above 500 ms and read the trace:
   which span takes the time.
2. Pool waits on [Runtime](http://127.0.0.1:3000/d/runtime): `hikaricp_connections_pending` > 0 means the pool is exhausted; GC pause share
   rising means memory (check its container against its limit).
3. The quote lookup calls banking for FX rates: `http_client_requests_seconds` on [RED: logistics-service](http://127.0.0.1:3000/d/red-logistics-service).

**Fix.** A slow dependency: restore it (the client times out at ADR 0013's limits). Memory: the container's limit
(ADR 0029). **Escalate** if no span explains it.
