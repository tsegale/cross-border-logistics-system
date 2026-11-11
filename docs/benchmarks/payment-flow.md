# Payment flow under load (ADR 0012, ADR 0013)

## Question
A booking answers `201 AWAITING_PAYMENT` at once; the payment relay then authorises a hold in banking-service and the
shipment becomes `BOOKED`. How long does a customer wait for `BOOKED`, how many shipments per second does the relay
pay, and does anything get lost or paid twice under load?

## Method
`benchmarks/k6/payment_flow.js`, through the gateway (`127.0.0.1:8080`):

- `setup()` opens one EUR payer account per VU, funds it with 1,000,000.00 EUR and prices one quote.
- Each iteration books a shipment with a new `Idempotency-Key`, then polls `GET /api/v1/shipments/{id}` every 100 ms.
- `booking_duration` is the `POST /api/v1/shipments` as the customer sees it; `time_to_booked` runs from the booking
  request until the shipment reads `BOOKED`. A shipment not `BOOKED` within 30 s counts as `shipments_not_booked`
  and fails the run's threshold, but the relay keeps working on it.
- After every run: no shipment left in `AWAITING_PAYMENT` in logistics-db, and banking's
  `GET /api/v1/ledger/reconciliation` balanced. Both held after every run.

Two relay versions on the same stack, both with a 500 ms poll and a batch of 20:

- **sequential**: the Phase 2 relay as first committed; a claimed batch is sent one command at a time.
- **parallel (8)**: commit `10dcb34`; a batch is sent 8 commands at a time, the size of the payments bulkhead.

The sequential 8-VU run made 50 iterations per VU (400 shipments); every other run made 25 per VU. The rates are
per second, so they compare, but the 8-VU totals differ.

Machine: Intel Core i5-1135G7 laptop (4 cores / 8 threads), Windows 11 10.0.26300, Docker Desktop 29.7.2 on WSL 2
(8 CPUs, 3.7 GiB), host CPU load 90% from other applications. Default compose stack plus `compose.bench.yml`
(`unlocked` system accounts, HikariCP pool 32), Spring Boot 4.1.2, Temurin 21, PostgreSQL 17, k6 v2.3.0 on the host.

## Results

| Relay | VUs | Booked in 30 s | Not booked in 30 s | Booked/s | booking p50 ms | booking p95 ms | time to `BOOKED` p50 ms | p95 ms | p99 ms | max ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| sequential | 8 | 379 | 21 | 1.01 | 111 | 1,789 | 3,266 | 18,329 | 28,730 | 29,844 |
| sequential | 16 | 385 | 15 | 1.90 | 81 | 1,437 | 4,300 | 22,698 | 26,281 | 30,073 |
| parallel (8) | 8 | 200 | 0 | 8.16 | 59 | 451 | 702 | 1,662 | 1,811 | 1,901 |
| parallel (8) | 16 | 400 | 0 | 11.52 | 80 | 576 | 881 | 3,577 | 5,243 | 6,128 |

A first parallel 8-VU run, started seconds after logistics-service was recreated and with no warm-up, booked 197 of
200 within 30 s (2.15/s, `time_to_booked` p50 1,011 ms, p99 29,131 ms); the other 3 were booked shortly after. The
table shows the run repeated on the warm service. The cold run's summary is kept as
`benchmarks/out/payment-8-cold.json`.

## Reading the numbers
- **The relay was the bottleneck.** Sending a batch one command at a time capped payment at about one banking round
  trip per command: 1.0-1.9 booked/s, with shipments queueing for up to 30 s. Sending 8 at a time raised that to
  8.2/s at 8 VUs and 11.5/s at 16, and the p99 wait for `BOOKED` fell from 26-29 s to 1.8-5.2 s.
- **Booking itself is unaffected**: the `POST` writes the shipment and its command in one local transaction and does
  not call banking, so its latency stays at 59-131 ms p50 whatever the relay does.
- **At 16 VUs the parallel relay is no longer the only limit**: booked/s grows 1.4x for 2x the VUs while the wait
  grows. On this machine every service and both databases share 8 vCPUs with a host already at 90% load; the next
  step would be a profile, not a guess.
- **No losses, no duplicates**: every shipment of every run ended `BOOKED`, and reconciliation balanced after each.

## Fault injection
`BankingFaultInjectionTest` runs the real banking-service image and its PostgreSQL behind Toxiproxy (Testcontainers).
Every test ends by checking banking holds exactly one hold for the shipment and its reconciliation is clean. All five
pass with the parallel relay, in two consecutive runs and in the full `verify`:

| Fault | Outcome |
| --- | --- |
| Banking slower than the 2 s read timeout | Call times out, outcome unknown, retried with the same key; banking replays the committed hold: one hold, `BOOKED` |
| Banking unreachable, then back | Commands retried with backoff while down; `BOOKED` once banking is back, one hold |
| Connection reset after banking committed the hold | Response lost; the retry with the same `Idempotency-Key` returns the original hold, not a second one |
| Banking answers `503 database-unavailable` while its database is paused | Treated as unknown and retried; `BOOKED` once the database is back; a command that exhausts 8 attempts is `PARKED` for an operator |
| Banking down long enough to open the circuit breaker | Breaker opens and calls fail fast; after the wait it goes half-open, trial calls succeed, it closes and every shipment is `BOOKED` with one hold |
