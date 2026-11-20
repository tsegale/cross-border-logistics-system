# Event catalogue

Every event FreightFlow publishes, where it goes, who reads it and what order it keeps. The contracts are the JSON
Schemas in [`events/`](events/); each has an example. Decisions: envelope and evolution in
[ADR 0023](adr/0023-event-envelope-and-schema-evolution.md), personal data in
[ADR 0024](adr/0024-personal-data-stays-out-of-events.md), the outbox in [ADR 0025](adr/0025-transactional-outbox-relay.md),
consumers in [ADR 0026](adr/0026-consumer-idempotency-retries-and-ordering.md), Kafka access in
[ADR 0022](adr/0022-kafka-authentication-acls-and-topics.md).

## Common to every event
- **Envelope** ([schema](events/envelope.schema.json)): `eventId` (UUIDv7), `type`, `schemaVersion`, `occurredAt`,
  `aggregateId`, `sequence`, `actor`, `requestId`, `payload`.
- **Key**: the aggregate id. **Headers**: `event-id`, `event-type`, `schema-version`, `aggregate-id`, `sequence`.
- **Published** through the producer's transactional outbox: an event exists if and only if its change committed,
  and is delivered **at least once**. A relay that died after sending sends it again; consumers drop the copy by
  `eventId`.
- **Order**: per aggregate. `sequence` is 1, 2, 3, ... per aggregate in commit order, and the relay never has two
  events of one aggregate in flight, so they reach the partition in that order (a resend can repeat an earlier one,
  never skip ahead). There is no order between aggregates.
- **Money** is a decimal string with up to 4 places. **No personal data**: ids, amounts, states and codes only.
- **Topics**: `freightflow.<aggregate>.v<n>`, 30 days' retention, `delete` cleanup; one owner writes, the consumers
  listed below read as their own group (`infra/kafka/acls.conf`).

## Topics

| Topic | Producer | Partitions | Consumers (group) |
| --- | --- | --- | --- |
| `freightflow.account.v1` | banking-service | 3 | |
| `freightflow.journal-entry.v1` | banking-service | 6 | |
| `freightflow.hold.v1` | banking-service | 6 | logistics-service, notification-service |
| `freightflow.approval.v1` | banking-service | 3 | |
| `freightflow.shipment.v1` | logistics-service | 6 | notification-service |
| `freightflow.hold.v1.logistics-service.dlt` | logistics-service | 1 | (a copy; operators use `/api/v1/logistics/dead-letters`) |

Topics without a consumer today exist for the ones to come (reporting, audit) and cost nothing while idle.

## Events

### banking-service

| Type | Topic | Aggregate (key) | When | Consumers |
| --- | --- | --- | --- | --- |
| [`account.opened`](events/account.opened.schema.json) | account.v1 | account | a customer account was opened | |
| [`journal-entry.posted`](events/journal-entry.posted.schema.json) | journal-entry.v1 | journal entry | any money movement: deposit, transfer, FX transfer, reversal, capture; with its postings | |
| [`hold.authorised`](events/hold.authorised.schema.json) | hold.v1 | hold | money reserved for a charge | |
| [`hold.extended`](events/hold.extended.schema.json) | hold.v1 | hold | an active hold's expiry moved later | |
| [`hold.captured`](events/hold.captured.schema.json) | hold.v1 | hold | the charge was taken (with the capture's journal entry) | logistics, notification |
| [`hold.released`](events/hold.released.schema.json) | hold.v1 | hold | the reservation was given back without a charge | notification |
| [`hold.expired`](events/hold.expired.schema.json) | hold.v1 | hold | banking's sweeper closed a hold nobody captured or released; actor null | logistics, notification |
| [`approval.decided`](events/approval.decided.schema.json) | approval.v1 | approval | a four-eyes request was approved, rejected or expired (ADR 0021) | |

A hold's events are ordered: `hold.authorised` (1), any `hold.extended`, then one of `captured`, `released`,
`expired`. A capture also writes a `journal-entry.posted` on its own entry, with no order to the hold's events.

### logistics-service

All on `freightflow.shipment.v1`, keyed and sequenced by the shipment, so one shipment's events are one ordered
history.

| Type | When | Consumers |
| --- | --- | --- |
| [`shipment.booked`](events/shipment.booked.schema.json) | a customer booked from a quote; status `AWAITING_PAYMENT`; always sequence 1 | notification |
| [`shipment.status-changed`](events/shipment.status-changed.schema.json) | the status moved: paid (`BOOKED`), refused (`PAYMENT_FAILED`), cancelled, or a tracking event applied | notification |
| [`shipment.payment-failed`](events/shipment.payment-failed.schema.json) | banking refused a payment command (authorise, capture, release, extend, re-authorise) | notification |
| [`tracking-event.recorded`](events/tracking-event.recorded.schema.json) | a carrier or operator reported a tracking event, applied or not | |

A tracking event that moves the shipment writes `tracking-event.recorded` and then `shipment.status-changed`, with
consecutive sequence numbers and the same `trackingEventId`.

## Examples

`hold.captured` (from its schema):

```json
{
  "eventId": "0193c2a1-5b6c-7d7e-9f80-91a2b3c4d5e6",
  "type": "hold.captured",
  "schemaVersion": 1,
  "occurredAt": "2026-11-24T06:40:11.902Z",
  "aggregateId": "0193a5b2-80aa-7e3c-a1b2-c3d4e5f60718",
  "sequence": 3,
  "actor": {"subject": "9d403f51-6e70-4182-9d2e-3f4a5b6c7d8e", "clientId": "logistics-service", "organizationId": null},
  "requestId": "shipment-0193a5b2-7c4e-7d1a-9f3b-2c5d8e1f4a6b-capture",
  "payload": {
    "hold": {
      "holdId": "0193a5b2-80aa-7e3c-a1b2-c3d4e5f60718",
      "payerAccountId": "01938a6e-2f10-7b4c-8d5e-6f708192a3b4",
      "organizationId": "0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091",
      "reference": "SHIPMENT 0193a5b2-7c4e-7d1a-9f3b-2c5d8e1f4a6b",
      "status": "CAPTURED",
      "amount": "1840.5000", "currency": "EUR",
      "payerAmount": "1840.5000", "payerCurrency": "EUR",
      "fxRate": null,
      "expiresAt": "2026-11-27T00:00:00Z"
    },
    "capturedAmount": "1840.5000",
    "capturedPayerAmount": "1840.5000",
    "journalEntryId": "0193a5d0-11f2-7a3b-b4c5-d6e7f8091a2b"
  }
}
```

`shipment.booked`:

```json
{
  "eventId": "0193a5b2-7c4f-7e2b-a04c-3d6e9f2a5b7c",
  "type": "shipment.booked",
  "schemaVersion": 1,
  "occurredAt": "2026-11-20T08:20:13.870Z",
  "aggregateId": "0193a5b2-7c4e-7d1a-9f3b-2c5d8e1f4a6b",
  "sequence": 1,
  "actor": {"subject": "6a1f0c2e-3b4d-4e5f-8a9b-0c1d2e3f4a5b", "clientId": "freightflow-web", "organizationId": "0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091"},
  "requestId": "3c4d5e6f-7a8b-4c9d-8e0f-1a2b3c4d5e6f",
  "payload": {
    "shipmentId": "0193a5b2-7c4e-7d1a-9f3b-2c5d8e1f4a6b",
    "trackingNumber": "FF-0000004D-K",
    "organizationId": "0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091",
    "payerOrganizationId": "0b3c4d5e-6f70-4812-9a3b-4c5d6e7f8091",
    "quoteId": "0193a5b0-0f1e-7d2c-9b3a-4c5d6e7f8091",
    "origin": "NAWVB", "destination": "DEHAM",
    "mode": "SEA_LCL", "incoterm": "FCA",
    "status": "AWAITING_PAYMENT", "paymentStatus": "AUTHORISATION_PENDING",
    "quotedTotal": {"amount": "1840.5000", "currency": "EUR"},
    "chargeableWeightKg": "1250.0000",
    "plannedPickupDate": "2026-11-25"
  }
}
```

The other examples are in each schema's `examples`.

## Changing an event
1. Edit its schema in `docs/events/`. An optional property or a new enum value is compatible: add `schemaVersion`'s
   next value to the enum and raise `schemaVersion` in the producer's `Type`. Anything else needs a new topic version
   (ADR 0023).
2. Run the producer's `EventContractIntegrationTest` and `EventSchemaFilesTest`, and each consumer's example test.
3. CI's "Event schemas stay compatible" compares the schema with the one on `main`
   (`python3 scripts/check_event_schemas.py --base origin/main` locally).
