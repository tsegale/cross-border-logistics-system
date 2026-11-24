# 37. Carrier tracking in DCSA Track and Trace 2.2: signed webhooks, a polling fallback, and a carrier simulator

Date: 2026-11-24 · Status: Accepted · Builds on ADR 0009 (tracking events), ADR 0018 (carrier access), ADR 0025

## Context
Carrier events reached FreightFlow only as its own JSON (`POST /shipments/{id}/tracking-events`), typed in by a
carrier integration nobody has. Container lines calling at Walvis Bay and Hamburg publish events in the Digital
Container Shipping Association's Track and Trace standard (T&T). Carriers push them to a subscriber's callback URL,
or the subscriber pulls them.

## Sources, checked 2026-11-24
- **Versions.** DCSA's reference site lists Track and Trace v3.0.0 as "the latest available release". Its artefacts
  are published only through DCSA member and partner access
  (`snapshots.reference.dcsa.org/content/standards/releases/track-and-trace/v3-0-0`). The newest public OpenAPI
  document is **T&T 2.2.0** on SwaggerHub (`dcsaorg/DCSA_TNT/2.2.0`, Apache 2.0), with its event domain
  `EVENT_DOMAIN/1.0.4`. FreightFlow implements 2.2.0. Its resolved document is committed as
  `docs/api/dcsa-tnt-2.2.0.json`.
- **Signature.** `EVENT_DOMAIN/1.0.4`, parameter `notificationSignature`:
  - the header is `Notification-Signature: <signature-type>=<signature>`;
  - the signature "MUST cover the entire request body of the request including whitespace and newlines", as UTF-8
    bytes;
  - "None of the HTTP headers nor the request URL is covered";
  - the only supported type is `sha256`.

  The schema `secret` is "A Base64 encoded secret shared between the Publisher and the Subscriber", set when the
  subscription is created (`POST /v2/event-subscriptions`) or replaced (`PUT .../secret`). DCSA's reference
  implementation (DCSA-Event-Core, `MessageSignatureHandler`) computes the sha256 type as HMAC-SHA256 keyed with
  that secret. The callback also carries `Subscription-ID` and `API-Version`.
- **No timestamp is signed.** The standard defines no signed timestamp and no nonce. Each event has an `eventID`
  (UUID) and an `eventCreatedDateTime`.
- **Polling.** `GET /v2/events` filters by `equipmentReference` and by `eventCreatedDateTime` with operators
  (`eventCreatedDateTime:gte=...`), and pages with `limit` and `cursor`.
- **Equipment event codes**: LOAD, DISC, GTIN, GTOT, STUF, STRP, PICK, DROP, INSP, RSEA, RMVD. Every event also has
  an `eventClassifierCode`: ACT (actual), EST (estimated) or PLN (planned).
- **ISO 6346 container numbers**: a three-letter owner code, an equipment category (U, J or Z), six digits and a
  check digit. The check digit is the sum of each character's value times 2^position, modulo 11, where 10 counts
  as 0. Letters run from A = 10 to Z = 38, skipping multiples of 11. CSQU 305438 3 is the standard's worked example
  (`ContainerNumberTest`).

## Decision
- **A webhook per carrier**: `POST /api/v1/dcsa/notifications/{carrierId}`, routed through the gateway without a
  bearer token.
  - **Authentication.** A carrier proves itself with the signature. The request's Subscription-ID must be the one
    configured for that carrier, and the HMAC of the raw body with that subscription's secret must match the header
    (constant-time comparison). The secret is a compose secret (`dcsa_carrier_secret`, ADR 0019).
  - **Failure.** A mismatch is a 401 (`invalid-webhook-signature`) and nothing is stored. A body that is not a
    non-empty array of events with an eventID and an eventCreatedDateTime is a 400 (`malformed-dcsa-event`).
- **Replays are refused twice.**
  - **The window.** Any event created more than `replay-window` (24 h) ago, or more than `clock-skew` (5 min) in
    the future, refuses the whole notification with 400 `webhook-replay-rejected`. A captured notification is
    therefore useless after a day.
  - **The eventID.** Within the window, each event is stored once, `ON CONFLICT (id) DO NOTHING`, so a replayed
    notification is answered 204 and changes nothing.

  Since the standard signs no timestamp, the window rests on the signed body's own `eventCreatedDateTime`.
- **Answer fast, process later.** The webhook stores the events as sent (`dcsa_events`, the original JSON for audit)
  and returns 204. `DcsaEventProcessor` then works through them, leased like an outbox (ADR 0025), on every
  instance, with backoff and a FAILED state after 8 attempts.
- **Mapping to milestones** (`DcsaMapping`).
  - An equipment event finds its shipment by the container number among that carrier's active shipments
    (`PUT /shipments/{id}/carrier` now also takes the container number, ISO 6346 checked). Only actual events
    count.
  - Equipment codes: PICK and STUF are picked up; GTIN at the origin is at the origin port; LOAD at the origin is in
    transit; DISC at the destination is at the destination port; GTOT at the destination is out for delivery; DROP
    at the destination is delivered.
  - T&T 2.2 has no customs event: a gate-out after discharge means customs released the container, and
    CUSTOMS_CLEARED is recorded as skipped (ADR 0009).
  - Transport events (DEPA, ARRI) have no container, so they are stored and marked IGNORED. Matching them to
    shipments by vessel and voyage is left for when bookings carry a voyage.
- **The same path as every carrier event.** A mapped event is recorded through `recordTrackingEvent`, as the
  assigned carrier, with `dcsa:<eventID>` as its carrier event id. Duplicates, out-of-order events and skipped
  milestones are therefore handled exactly as in Phase 2 (ADR 0009), and the shipment's events and notifications
  follow.
- **Polling fallback.** For a carrier configured `mode: POLL`, `DcsaPoller` calls `GET <baseUrl>/v2/events` with
  `eventCreatedDateTime:gte` at the newest creation time seen (`dcsa_poll_cursors`). It runs behind the `dcsa-poll`
  breaker with 2 s and 10 s timeouts, and the events go into the same inbox. Polling is our own outbound call, so
  there is no signature to check; a carrier both pushed and polled applies each event once.
- **A carrier simulator in compose** (`infra/partners/carrier.py`, in the partner simulator).
  - `POST /_control/carrier/voyages` runs a container through PICK, GTIN, LOAD, DEPA, ARRI, DISC, GTOT and DROP,
    pushing each event signed to logistics, or keeping it for polling.
  - `/_control/carrier/forge` and `/replay` send a wrong-key notification and a stale one, for the drills.
  - The smoke test follows one shipment from booking to delivery on its events.

## Consequences
- Moving to T&T 3.0 is a change to the mapping and the parsing: the webhook, the inbox and the processor stay.
- A carrier's secret is shared between the carrier and FreightFlow. Rotating it means replacing the compose secret
  and the carrier's subscription secret together (`PUT /v2/event-subscriptions/{id}/secret`).
- A notification older than 24 h is refused even when it is genuine: a carrier recovering from a long outage
  resends inside a day, or the poller picks its events up.
- Observability:
  - Signature failures and replay refusals are counted (`freightflow_dcsa_notifications_total{outcome}`);
    `CarrierWebhookSignaturesFailing` opens a ticket.
  - Polling age is a gauge (`freightflow_dcsa_poll_age_seconds`); `CarrierPollingBehind` opens a ticket.
- Not built: managing subscriptions at the carrier (`POST /v2/event-subscriptions`), the HEAD check some carriers
  make on a callback URL, and shipment and transport events.
