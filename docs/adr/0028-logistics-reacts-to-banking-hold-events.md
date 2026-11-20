# 28. logistics reacts to banking's hold events; payment commands stay HTTP

Date: 2026-11-20 · Status: Accepted · Extends ADR 0011's revision and ADR 0012

## Context
logistics pays for shipments with payment commands to banking: authorise, extend, capture, release (ADR 0012),
sent by a relay over HTTP with deterministic idempotency keys. Each command's outcome comes back in its response, so
logistics knows what happened to everything it asked for.

Two things happen to holds that logistics did not ask for:
- **banking expires a hold.** Extensions (ADR 0011, revised) keep a hold alive until pickup, but an extension can
  fail to arrive in time: banking down through the renewal window, an extension parked for an operator, a shipment
  booked before V7 that is never extended. The hold then expires, the money is released, and the capture at pickup
  will be refused. Until Phase 4 logistics found out only then.
- **A capture logistics never sent.** Only logistics holds the `banking.holds` scope, so this should not happen. If
  it does (a bug, a misused credential, an operator script), money left a customer's account without a pickup.

Since Phase 4 banking publishes every hold change (`freightflow.hold.v1`, ADR 0023).

## Options
1. **Everything by events.** logistics publishes commands, banking consumes them and answers with events. Fully
   asynchronous, but the saga loses what HTTP gives it now: an immediate, typed answer per command (a 409
   `insufficient-funds` with the available balance), the idempotency-key replay, the circuit breaker and bulkhead
   around one dependency (ADR 0013). Correlating answers to commands, timeouts for answers that never come, and two
   more topics would replace them.
2. **Everything by HTTP.** logistics polls banking for its holds' states. Simple, but a poll interval short enough to
   catch an expiry before pickup multiplies requests by the number of open shipments, and banking would need a "holds
   changed since" query.
3. **Split by who started it.** What logistics asks for stays a command over HTTP (request, response, retry with the
   same key). What banking does on its own reaches logistics as an event.

## Decision
**Option 3.**

- **Commands stay HTTP** (ADR 0012), unchanged. Their answers are also published as events, which logistics' consumer
  reads and ignores (`hold.authorised`, `hold.extended`, `hold.released`): it already recorded them from the
  response, and acting twice would need reconciling two sources of the same fact.
- **`hold.expired`**: if the shipment named by the hold's reference (`SHIPMENT <id>`) is still `BOOKED` (waiting for
  pickup) on exactly that hold with payment `AUTHORISED`, logistics **re-authorises**: the payment goes back to
  `AUTHORISATION_PENDING` and a `REAUTHORISE` command for the full amount goes into the payment outbox, with the key
  `shipment-<id>-reauthorise-<expired hold id>` and an expiry from `HoldExpiryPolicy.reauthorisationExpiry` (towards
  the pickup target, or the overdue step if the pickup is near or past, capped at 29 days). This is the option ADR
  0011's revision rejected as the *normal* path (it takes a new FX rate and a new hold id); as the *recovery* after an
  expiry it has none of those costs, because the old hold no longer reserves anything and no capture can be in flight
  on it while the shipment waits for pickup.
  - Accepted: the shipment is `AUTHORISED` on the new hold. If it was picked up or cancelled meanwhile, the new hold
    is captured or released at once (`settleAfterStatusChange`).
  - Refused (the customer no longer has the money): payment `FAILED` with banking's problem, the shipment stays
    `BOOKED`, `shipment.payment-failed` is published, and an operator decides.
  - Anything else (picked up, cancelled, already on another hold, unknown shipment) is left alone.
- **`hold.captured`** without a CAPTURE command of logistics for that hold: `freightflow.payment.unexpected.captures`
  goes up and an error is logged with the hold and the shipment. Nothing is reversed automatically: a reversal needs
  two operators (ADR 0021), and the cause has to be understood first. Alert: any increase (docs/operations.md).
- The consumer is idempotent and keeps order (ADR 0026): group `logistics-service`, offsets committed per record,
  `processed_events` written in the transaction that acts, blocking retries with backoff, then
  `freightflow.hold.v1.logistics-service.dlt`; a poison message goes there at once.

## Consequences
- A hold that expires before pickup now heals itself while the customer has the money, instead of failing at pickup.
- The re-authorised hold takes the current FX rate; for a cross-currency shipment the amount the customer pays can
  differ from the first hold's. The quote's price in its own currency does not change.
- logistics depends on the broker only for these two reactions. With the broker down, payment commands keep working;
  an expiry is noticed when the broker is back (the topic keeps 30 days).
- A shipment whose capture command is already pending when its hold expires (picked up just after the expiry) is not
  re-authorised: the capture is refused with `hold-expired` and the payment is `FAILED` for an operator, as before.
