# 11. Holds: authorise, capture, release

Date: 2026-11-11 · Status: Accepted

## Context
A shipment is booked days before its cargo is picked up, and only then is the freight owed. Charging at booking
means refunding every cancellation; charging at pickup means the money may be gone by then. Card schemes solve the
same problem with an authorisation: reserve the amount now, take it later, or let it go. logistics-service needs
exactly that from banking-service, over a network that can fail at any point (ADR 0012).

The freight is priced in the tariff currency (EUR on every seeded lane, ADR 0008) while the payer's account may be
in NAD or ZAR. Banking owns FX (Phase 2 fix 0.5), so the conversion has to happen in banking, at banking's rate,
and the capture must use the rate the customer authorised, not whatever the rate is on pickup day.

## Options
1. **Postings into a suspense account.** Authorise posts customer -> CUSTOMER_HOLDS; capture posts
   CUSTOMER_HOLDS -> FREIGHT_REVENUE; release posts it back. The reservation is visible in the ledger, but every
   authorisation and every release becomes two journal entries that move no money between parties, the customer's
   statement fills with reservations and their reversals, and "ledger balance" stops meaning what the customer owns.
2. **A holds table read into the available balance.** A hold row records the reservation; the customer's
   `available balance = ledger balance - active holds`. Only the capture is a journal entry.

## Decision
Option 2.

- **Representation.** `holds` (V3) stores the charge amount and currency, the amount reserved on the payer account in
  its own currency, the locked FX rate, status (`ACTIVE`, `CAPTURED`, `RELEASED`, `EXPIRED`), and the capture's
  journal entry. `accounts.held_amount` caches the sum of the account's active holds and is updated under the same
  `SELECT ... FOR UPDATE` as the balance, so the two never disagree; reconciliation checks the cache against the
  active holds as well as the balances against the postings.
- **The customer never overdraws through holds.** `CHECK (account_type <> 'CUSTOMER' OR balance >= held_amount)`.
  A new hold needs `available >= amount`; every customer debit, transfers included, needs `balance - debit >=
  held_amount` (`Account.applyPosting`, and the `conditional-update` transfer strategy's `UPDATE ... WHERE
  balance - held_amount >= ?`).
- **Capture** (`POST /api/v1/holds/{id}/capture`, full or partial, once) frees the whole reservation and posts one
  `CAPTURE` journal entry in the same transaction: payer -> `FREIGHT_REVENUE` in the charge currency, or, when the
  currencies differ, payer -> `FX_POSITION-<payer ccy>`, `FX_POSITION-<charge ccy>` -> `FREIGHT_REVENUE` at exactly
  the rate locked on the hold (4 postings, `journal_entries.fx_rate` = that rate). A partial capture's payer debit is
  `round(captured / rate)`, never above the reserved amount. `CHECK` constraints keep `captured <= amount` and make
  `CAPTURED` the only status with a journal entry.
- **Release** (`POST /api/v1/holds/{id}/release`) frees the reservation and posts nothing.
- **Concurrency.** Every change to a hold locks the payer's account row first and the hold row second. A capture, a
  release and the expiry sweeper racing on one hold therefore serialise, and exactly one of them acts: the others
  find it no longer `ACTIVE` (`409 hold-not-active`, with the status). Verified 10 times in a row in
  `HoldIntegrationTest.concurrentCaptureAndReleaseOfOneHoldResolveToExactlyOne`; 20 concurrent holds of 100.00 on a
  1,000.00 account create exactly 10.
- **Idempotency.** Authorise, capture and release all take an `Idempotency-Key` (scopes `hold`, `hold-capture:<id>`,
  `hold-release:<id>`), so logistics can resend any of them after a lost response and get the original answer.
- **FX.** A hold whose charge currency differs from the payer account's takes `ExchangeRateProvider`'s customer rate
  at authorisation (`payer amount = charge / rate`, HALF_EVEN) and stores rate, mid rate and spread on the hold.
- **Expiry.** Default 7 days (`holds.default-ttl`), at most 30 (`holds.max-ttl`). A capture after expiry is
  `409 hold-expired`. `HoldExpirySweeper` runs every minute on every instance: it reads expired candidates without a
  lock and expires each one in its own transaction under the same two locks, re-checking the status, so two
  instances never release one hold twice and a sweep never blocks a capture for longer than one hold.
- **Accounts** show `ledgerBalance` and `availableBalance`. The statement is unchanged: holds are not postings.
- **Internal.** `/api/v1/holds/**` is not routed by the gateway (ADR 0015); logistics reaches it on the compose
  network. Phase 3 adds service-to-service credentials.

## Consequences
- The ledger records only real money movement; a cancelled shipment leaves no trace in it.
- The available balance is one subtraction on the locked row, not a sum over holds.
- A hold is not money: banking's trial balance does not see reservations. Anyone who needs them reads `holds`.
- A partial capture releases the rest at once; a second capture of one hold is impossible by design.

## Revision (Phase 3): a hold lasts until pickup

Date: 2026-11-11

### Problem
logistics never sent `expiresAt`, so every hold got the 7-day default. A sea shipment can be booked weeks before its
cargo is picked up; its hold expired, the capture at pickup was refused (`409 hold-expired`), and the shipment
travelled unpaid with no recovery path.

### Options
1. **Re-authorise and release the old hold.** Works with the Phase 2 API. But for a moment the customer needs the
   amount available twice (the new hold is made before the old one is let go, or there is a window with no hold at
   all), a cross-currency hold gets a new rate on every renewal, and the shipment's hold id changes under a capture
   that may already be in flight.
2. **Extend the hold in place** (`POST /api/v1/holds/{id}/extension`). The reservation, the amount and the locked
   rate stay as they are; only `expires_at` moves later. One more endpoint.

### Decision
Option 2.

- **Banking.** `POST /api/v1/holds/{id}/extension` with `{"expiresAt": ...}` and an `Idempotency-Key` (scope
  `hold-extension:<id>`). It takes the same two locks as every other hold change, so it serialises with a capture,
  a release and the sweeper. The new expiry must be later than the current one, at most `holds.max-ttl` (30 days)
  from now, and at most `holds.max-lifetime` (120 days) from the hold's creation: an extension is not a way to keep
  money reserved, or an FX rate locked, indefinitely. A hold that is no longer active is `409 hold-not-active`; one
  past its expiry, even if not yet swept, is `409 hold-expired`. Every extension is a row in `hold_extensions` (V5),
  append-only under the ledger's trigger.
- **logistics.** A booking now requires `plannedPickupDate` (UTC), between today and 90 days ahead
  (`max-pickup-horizon`). The AUTHORISE asks for `expiresAt` = end of the pickup day + 3 days (`margin`), capped at
  29 days from now (`hold-expiry.max-ttl`, one day under banking's so clock skew between the services never gets an
  expiry refused). The hold's expiry, as banking returns it, is stored on the shipment.
- **Renewal.** `HoldRenewalJob` (hourly) finds `BOOKED` shipments with an authorised hold that expires within 3 days
  (`renew-before`) and writes an `EXTEND` payment command to the outbox. The relay sends it like any other command:
  same retries, backoff, breaker and parking (ADR 0012, ADR 0013). The key is
  `shipment-<id>-extend-<epoch second of the new expiry>`, and a partial unique index allows one open
  (`PENDING` or `PARKED`) extension per shipment, so the job is safe on every instance at once. Each extension
  reaches towards the pickup target, capped at 29 days; a shipment still waiting after its planned pickup gets 7 days
  more at a time (`overdue-extension`) until banking's 120-day lifetime ends it.
- **Outcomes.** A successful extension updates the shipment's hold expiry (never backwards). An extension that lost
  the race to a capture or release (`hold-not-active` with `CAPTURED` or `RELEASED`) is moot and succeeds without a
  change. Any other refusal, such as the lifetime limit, marks the payment `FAILED` with banking's problem, for an
  operator.
- **Tested** with an injected clock (`HoldRenewalIntegrationTest`): a pickup 60 days out is authorised for 29 days,
  extended twice (to 29 days again, then to the pickup target), and captured at pickup; a moot extension, a refused
  one and a picked-up shipment are covered too. Banking's rules are in `HoldIntegrationTest`.

### Consequences
- A shipment can no longer be booked without a pickup date. Shipments booked before V7 keep banking's default expiry
  and are not extended.
- The FX rate locked at booking holds for up to 120 days. That is FX risk the forwarder carries for long bookings;
  pricing it is a business decision outside this ADR.
- Pickup dates are dates, not times: a shipment collected late on its pickup day is still covered by the margin.
