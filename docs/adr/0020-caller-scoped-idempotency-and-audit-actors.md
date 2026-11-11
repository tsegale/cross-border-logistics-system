# 20. Caller-scoped idempotency keys and audit actors

Date: 2026-11-11 · Status: Accepted

## Context
ADR 0006 stored idempotency keys per `(scope, key)`. With identities, that is a cross-tenant leak: two customers who
both send `Idempotency-Key: order-1` collide, and the second either gets a 422 for "another payload" or, worse, the
first customer's stored response, account numbers and amounts included. Separately, nothing recorded who moved money:
a journal entry knew what happened, not who asked.

## Decision
- **Keys are scoped to the caller**: `(scope, caller, key)`, where caller is the token's client id plus
  `org:<organisation id>` for a customer, or `sub:<subject>` for anyone without an organisation (staff, carriers,
  logistics-service). Members of one organisation share a key space: two people of the same company retrying the same
  booking get one booking. The advisory lock that serialises a key includes the caller too. Rows stored before Phase 3
  migrate with caller `legacy`, which no token produces, so they can never be replayed to anyone (banking V7,
  logistics V8). Tested across tenants in both services' `AuthorisationMatrixTest`: same key, two organisations, two
  independent results, each replay returning its own.
- **Every money movement and every change records its actor**: subject, client id, organisation where there is one,
  and the request id (ADR 0015).
  - banking: `journal_entries` (deposits, transfers, FX transfers, reversals, captures) and `holds` (plus
    `on_behalf_of_organization_id` and `on_behalf_of_subject`, as logistics vouches for them). A trigger refuses a new
    journal entry or hold without an actor, so a code path that forgets one fails instead of writing an anonymous
    movement.
  - logistics: who booked a shipment (a trigger refuses a booking without one), who cancelled it, who recorded each
    tracking event (also enforced by a trigger), and who retried a parked payment command and when.
- **Shown to staff only.** A journal entry, a statement line and a shipment carry `actor` / `audit` for operators and
  auditors; a customer's view leaves them out.

## Consequences
- Customer statements are unchanged; an operator's statement answers "who did this" without a log search.
- The actor of a capture is logistics-service (the request id is the payment command's key); the person behind it is
  on the hold, recorded at authorisation.
- Audit columns are part of the append-only rows they describe: they cannot be edited afterwards either.
