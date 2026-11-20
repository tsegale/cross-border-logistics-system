# 24. Personal data stays out of events

Date: 2026-11-20 · Status: Accepted

## Context
Shipments name people: shipper and consignee names, addresses, phone numbers and emails, plus a free-text goods
description and the payer's account number. Banking holds account numbers and operators' free-text references,
reasons and comments. Notification emails need a recipient address.

A Kafka topic is a log. FreightFlow keeps 30 days (ADR 0022); every consumer may copy what it reads into its own
store, and a replay copies it again. GDPR (DSGVO) Art. 17 gives a data subject the right to erasure, Art. 5(1)(c)
asks for data minimisation, and Art. 25 for data protection by design. An erasure request cannot reach every
partition replica, consumer database, dead-letter topic and backup that a personal field travelled to; deleting
single records from an immutable log is not something Kafka offers (a compacted topic can tombstone a key, but these
topics are not compacted and must not be, ADR 0022).

## Options
1. **Events carry personal data**, consumers keep their own copy. Simple consumers; erasure becomes a hunt through
   every system that ever read the topic.
2. **Crypto-shredding**: encrypt personal fields with a per-person key held by the owner, delete the key to erase.
   It works on immutable logs, but needs a key service, key lookups on every read, and every consumer must handle
   "key gone".
3. **Ids and business facts only.** Events say what happened to which aggregate, with ids, amounts, states and
   codes. A consumer that needs a name or an address asks the owning service, which applies its own access rules and
   its own erasure.

## Decision
**Option 3.** No event carries a name, an email, a postal address, a phone number, an account number, a goods
description or any free text a person typed (references, reasons, comments, cancellation reasons, carrier
descriptions, banking's problem detail, which can name an account).

What events do carry:
- ids: shipment, hold, account, journal entry, approval, tracking event, organisation;
- the tracking number, a public business identifier printed on the waybill, not a person's;
- amounts, currencies, statuses, UN/LOCODEs, Incoterms, problem type URIs;
- `actor.subject` and `requestedBy.subject`: the Keycloak user id. It is pseudonymous data under GDPR (Recital 26):
  only Keycloak links it to a person, and deleting the Keycloak user removes the link. Schemas mark these fields
  `x-pseudonymous: true` so they are deliberate, not accidental.

notification-service keeps recipient addresses in its own database, entered by the organisation through
`/api/v1/notification-settings` (ADR 0027), and deletes them with the setting. It never learns a shipper's or a
consignee's details from an event.

**Enforcement**
- Payload schemas have `additionalProperties: false`, and the producer tests validate every event the services write
  (ADR 0023): a field that is not in the schema cannot be published.
- `EventSchemaFilesTest` (banking and logistics) and `scripts/check_event_schemas.py` (CI) fail if any schema marks a
  property `x-personal-data: true`, or names one the way personal data is named (`*name`, `*email*`, `*phone*`,
  `*address*`, `*contact*`, `*accountNumber`, `iban`, ...).
- logistics' `theBookingEventCarriesNothingOfTheParties` books a shipment and checks its event contains none of the
  booking's names, addresses, contacts, goods or account number.

## Consequences
- An erasure request touches Keycloak and the owning service's database, not Kafka, not the consumers.
- Consumers that show a person's details make a call to the owner and depend on it being up; notification-service
  avoids that by holding its own recipients.
- The name check can misfire on a harmless property name; renaming the property is the fix, since a name that reads
  like personal data misleads people too.
- A schema can still be wrong about what a field contains (a "reference" that a person fills in). Free-text fields are
  therefore excluded by rule, not by name.
