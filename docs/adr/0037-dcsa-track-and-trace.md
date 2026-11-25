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
- **How events link to bookings** (checked 2026-11-25 in the committed `dcsa-tnt-2.2.0.json`). A transport event
  (`transportEventTypeCode` ARRI or DEPA) is about a vessel's call at a port and has no `equipmentReference`. Both
  equipment and transport events carry a `transportCall` (`transportCallID`, `exportVoyageNumber`,
  `importVoyageNumber`, the deprecated `carrierVoyageNumber`, `UNLocationCode` and a `vessel` whose required
  `vesselIMONumber` is seven digits) and optional `documentReferences`, pairs of `documentReferenceType` BKG (the
  carrier's booking reference, `carrierBookingReference`, at most 35 characters) or TRD (transport document) and a
  `documentReferenceValue`.
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
  - An equipment event finds its shipments by the container number among that carrier's active shipments
    (`PUT /shipments/{id}/carrier` now also takes the container number, ISO 6346 checked). Every one of them: LCL
    shipments consolidated in one container share its events (Phase 7; before, only the newest was moved). If no
    shipment has the container, its booking references are tried. Only actual events count.
  - Equipment codes: PICK and STUF are picked up; GTIN at the origin is at the origin port; LOAD at the origin is in
    transit; DISC at the destination is at the destination port; GTOT at the destination is out for delivery; DROP
    at the destination is delivered.
  - T&T 2.2 has no customs event: a gate-out after discharge means customs released the container, and
    CUSTOMS_CLEARED is recorded as skipped (ADR 0009).
  - Transport events (DEPA, ARRI; Phase 7) find their shipments the two ways the standard links them, and every
    shipment either way gets the event:
    - by booking: each BKG `documentReferenceValue` against the shipment's `carrierBookingReference`, which
      `PUT /shipments/{id}/carrier` now also takes (1 to 35 of letters, digits and `/ _ . -`);
    - by vessel and voyage: when an equipment event of a shipment names a vessel, its IMO number and export and
      import voyage are recorded against the shipment (`shipment_transport_calls`). A transport event of the same
      carrier, vessel and a voyage number in common reaches those shipments. A vessel's next voyage has another
      number, so last month's cargo is not moved by this month's arrival.

    Each shipment gets the milestone for its own origin and destination: DEPA at its port of loading is in transit,
    ARRI at its port of discharge is at the destination port, a call anywhere else (a transhipment port) is
    recorded as IGNORED for that shipment. Which shipments an event reached, how, and with which tracking event is
    in `dcsa_event_shipments`.
  - A transport event that matches nothing yet is not written off for good: the equipment event that names its
    vessel may be processed on another instance at the same moment, or arrive later (a carrier's events need not
    come in order). It is retried on the inbox's backoff (1 s doubling, 8 attempts: one to two minutes, for the
    race) and then marked UNMATCHED, not FAILED. When an equipment event later records a shipment on that vessel
    and voyage, the carrier's UNMATCHED transport events of the same vessel and voyage from the last 30 days go
    back to PENDING and reach it (`DcsaTrackingIntegrationTest`).
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
    pushing each event signed to logistics, or keeping it for polling. LOAD, DISC, DEPA and ARRI name the vessel
    and voyage, and every event the booking when one is given.
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
  make on a callback URL, and shipment events (a document's status, not the cargo's whereabouts). TRD references
  are not matched: FreightFlow does not store the carrier's transport document number.
- A shipment's vessel is learnt from its own container's events. One assigned without a container or a booking
  reference is reached by no transport event; an operator sets one of them (`PUT /shipments/{id}/carrier`).
