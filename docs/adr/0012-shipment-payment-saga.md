# 12. Paying for a shipment: a saga with unknown outcomes

Date: 2026-11-11 · Status: Accepted

## Context
Booking a shipment (logistics-service) and reserving its freight on the customer's account (banking-service)
touch two databases (ADR 0001), so they cannot be one transaction. The call between them can fail in three
different ways, and they must be told apart:

1. **banking said no**: insufficient funds, a frozen or unknown account. Retrying the same request will never help.
2. **banking said nothing**: connection refused, a timeout, a 5xx. banking may not have seen the request.
3. **banking acted but its answer was lost**: the connection reset after banking committed the hold. A naive
   retry creates a second hold for the same shipment.

Cases 2 and 3 look identical from logistics. The outcome is *unknown*.

## Options
1. **Call banking inside the booking request.** The customer waits on banking, a banking outage blocks bookings,
   and a crash between banking's commit and logistics' commit leaves a hold with no shipment.
2. **Two-phase commit / XA across the two databases.** Couples both services' availability and is not supported
   by the stack here, for good reasons.
3. **Orchestrated saga through a transactional outbox of commands**, made safe to retry by idempotency keys.

## Decision
Option 3.

- **Outbox.** Booking writes the shipment (`AWAITING_PAYMENT`) and an `AUTHORISE` row in `shipment_payments` (V6) in
  one transaction: the command exists if and only if the shipment does. Amount, currency and payer are copied from
  the quote, and the idempotency key is derived from the shipment id: `shipment-<id>-authorise`.
- **Relay.** `PaymentCommandRelay` runs every 500 ms on every instance. It claims due `PENDING` commands with
  `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING id`, pushing `next_attempt_at` out by a 30 s
  lease, so instances never take the same command and never block each other. The HTTP call runs outside any
  database transaction; the outcome is recorded in a new one. If an instance dies mid-call the lease runs out and
  another instance sends the command again, with the same key. ShedLock was not needed: `SKIP LOCKED` gives
  per-row exclusivity, which is finer than a job lock.
- **Outcomes** (`BankingPayments`, `PaymentOutcomeRecorder`):
  - `2xx`: `SUCCEEDED`. AUTHORISE moves the shipment to `BOOKED` and stores the hold id.
  - business `4xx` (any problem type except the retryable ones below): `FAILED`, no retry. AUTHORISE moves the
    shipment to `PAYMENT_FAILED` and records banking's problem `type` and `detail` on it.
  - timeout, connection error, `5xx`, `408`, `429`, `409 idempotency-key-in-flight`, `409 transaction-conflict`:
    **unknown**. The command stays `PENDING` and is retried after `1 s x 2^(attempt-1)` (capped at 5 min, with
    jitter) with the **same** idempotency key. banking replays a hold it already committed (ADR 0006, 0011) instead
    of creating a second one.
  - after 8 unknown attempts (about 4 minutes) the command is `PARKED`, counted by the
    `freightflow.payment.commands.parked` gauge for alerting, and listed and retried by an operator through
    `GET /api/v1/payment-commands?status=PARKED` and `POST /api/v1/payment-commands/{id}/retry` (internal).
  - an attempt that never reached banking (circuit open, bulkhead full; ADR 0013) does not count as an attempt.
- **Capture and release** are commands in the same table and relay. A shipment reaching `PICKED_UP`, or any later
  milestone when the pickup scan was missed (ADR 0009), enqueues `CAPTURE`; cancelling a `BOOKED` shipment enqueues
  `RELEASE`, both in the transaction that changes the status. A shipment cancelled while its AUTHORISE was still in
  flight stays cancelled, and when the hold arrives the recorder enqueues its `RELEASE`. A capture or release that
  banking reports as already done (`409 hold-not-active` with status `CAPTURED`, or `RELEASED`/`EXPIRED`) counts as
  success: its first response was lost.
- **State machine.** `PAYMENT_FAILED` is final and reachable only from `AWAITING_PAYMENT`. Only the saga moves a
  shipment out of `AWAITING_PAYMENT`: a carrier event to `BOOKED` is stored as `REJECTED` (ADR 0009, revised).
  The shipment response carries `payment {status, holdId, amount, currency, failureType, failureDetail}`.
- **Optimistic locking.** The recorder and a tracking event may change one shipment at once; the recorder retries
  the `@Version` conflict like carrier ingestion does.

## Consequences
- A booking never waits on banking and succeeds while banking is down; payment catches up when it is back.
- Exactly one hold per shipment, whatever is lost on the way: one key per command, and banking's idempotency record
  answers every retry. The fault-injection tests (ADR 0013) end by checking this and a clean reconciliation.
- The status a customer sees lags the payment by up to one relay interval plus banking's latency.
- An idempotency record lives 24 h in banking (ADR 0006); a command parked for longer and then retried could be
  executed again by banking. Operators retry parked commands within that window; the parked gauge is the alarm.
- Phase 4 generalises the outbox to Kafka events.
