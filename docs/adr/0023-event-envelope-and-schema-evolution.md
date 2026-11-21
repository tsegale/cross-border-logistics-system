# 23. Event envelope, JSON Schema contracts and schema evolution

Date: 2026-11-20 · Status: Accepted

## Context
From Phase 4 banking and logistics publish events that other services act on: logistics re-authorises a hold banking
expired, notification-service emails customers. A consumer needs four things from every event, whatever its type:
- a unique id, to drop a duplicate (the outbox publishes at least once, ADR 0025);
- the aggregate and an order within it, to notice a gap or a stale event after a retry reordered them (ADR 0026);
- who caused it and in which request, to trace an email back to a click (ADR 0015, ADR 0020);
- a contract it can test against without a shared library (ADR 0002).

The contract has to survive change. Topics keep 30 days (ADR 0022), so a consumer deployed today reads events
written last month, and one deployed last month reads events written today. Both directions must work.

## Options
**Envelope**
1. **Own envelope**, the routing fields in the body and repeated in Kafka headers.
2. **CloudEvents** (CNCF): standard attribute names (`id`, `source`, `type`, `time`, `subject`) and a Kafka binding.
   No attribute for a per-aggregate sequence or the actor, so both would be extensions; its value is
   interoperability with tooling we don't run. The envelope keeps CloudEvents' meanings (`eventId` is `id`, `type` is
   `type`, `occurredAt` is `time`, `aggregateId` is `subject`), so moving to it later is a mapping, not a redesign.

**Schema format and registry**
1. **JSON Schema files in the repository** (`docs/events/`), checked in CI: producer tests validate every event they
   write, consumer tests deserialise every example, a script compares each schema with the one on `main`.
2. **Avro on a schema registry** (Confluent Schema Registry or Apicurio). Compact binary encoding, schema id in each
   record, and the registry enforces a compatibility mode (BACKWARD, FORWARD, FULL) at publish time. The cost: a
   registry service in the stack (another container, its own storage, its own availability: producers cannot publish
   if it is down and has not cached the schema), generated classes or `GenericRecord` in each service, and events no
   longer readable with `kafka-console-consumer`. Confluent's registry is under the Confluent Community License, not
   OSI-open; Apicurio is Apache 2.0.
3. **Protobuf on a registry.** Same registry trade-off; field numbers make evolution rules explicit, and it is the
   strongest choice when gRPC is also used. FreightFlow has no gRPC.

## Decision
**An own envelope, JSON on the wire, JSON Schema 2020-12 files in `docs/events/`, compatibility checked in CI.**

The envelope (`docs/events/envelope.schema.json`):

| Field | Meaning |
| --- | --- |
| `eventId` | UUIDv7, unique per event; consumers deduplicate on it |
| `type` | `<aggregate>.<what happened>`, e.g. `hold.captured` |
| `schemaVersion` | version of this type's schema within its topic version |
| `occurredAt` | when the producer made the change |
| `aggregateId` | the aggregate; also the Kafka record key |
| `sequence` | 1, 2, 3, ... per aggregate, without gaps, in commit order |
| `actor` | `{subject, clientId, organizationId}` from the token (ADR 0020), or null for a scheduled job |
| `requestId` | the request's `X-Request-Id`, the payment command's key for a payment outcome, or null |
| `payload` | the type's own fields |

Headers `event-id`, `event-type`, `schema-version`, `aggregate-id` and `sequence` repeat the routing fields, so a
consumer can filter or route without parsing the body. The key is the aggregate id, so one aggregate's events share a
partition and keep their order on it.

`sequence` comes from `event_sequences`, one row per aggregate, taken with an upsert in the transaction that writes
the event. The row stays locked until commit, so two transactions on one aggregate commit their numbers in order and
a number is never used twice. It survives pruning of the outbox (ADR 0025).

Money is a decimal string (`"1840.5000"`), never a JSON number: most JSON libraries read numbers as binary floats.

**Why JSON Schema over a registry, here.** Volumes are tens of events per second, so Avro's smaller records save
nothing that matters. What does matter here: no extra stateful service in a stack that runs on one laptop (README
memory budget), events readable in every tool, no generated code shared or duplicated between services, and the same
contract-in-the-repository model as the OpenAPI documents (ADR 0014), reviewed in the same pull request as the code
that changes it. The CI check gives the guarantee a registry's compatibility mode gives, before merge instead of at
publish time. With more producers, teams in other repositories, or volumes where record size costs money, a registry
(Apicurio, to stay open source) becomes the better trade, and the schemas would move into it unchanged in meaning.

**Evolution rules** (`scripts/check_event_schemas.py`, job "Event schemas stay compatible"). Compatibility is FULL:
old consumers read new events and new consumers read old events still on the topic.

| Change | Allowed on the same topic? |
| --- | --- |
| New optional payload property | Yes, with `schemaVersion` raised by one |
| New enum value | Yes, with `schemaVersion` raised by one; consumers treat an unknown value as "other" |
| Description, title, examples | Yes |
| Property removed or renamed, type changed, made nullable | No |
| Property made required, or a new required property | No |
| Enum value removed, pattern, format or length changed | No |
| Event moved to another topic, schema file deleted | No |

A breaking change goes to `freightflow.<aggregate>.v<n+1>` with new schema files; the producer writes both topics
until every consumer has moved, then stops writing the old one.

Payload schemas say `additionalProperties: false`, so a producer cannot publish a field the contract does not list
(this is what keeps personal data out, ADR 0024). Consumers deserialise tolerantly (unknown properties ignored, the
Jackson 3 default), which is what makes additions safe for them.

**Tests**
- Producer: `EventContractIntegrationTest` in banking and logistics drives every kind of change through the real
  services, then validates every row of the outbox against its type's schema (networknt json-schema-validator,
  formats asserted).
- Consumer: every service that consumes a type deserialises that schema's examples into its own records and asserts
  the fields it uses.
- `EventSchemaFilesTest`: every type a service publishes has a schema on the right topic, the code writes the newest
  `schemaVersion`, every example matches its schema.

## Consequences
- A schema change is a pull request that touches `docs/events/`; CI refuses an incompatible one.
- Events are larger than Avro would make them (the `hold.captured` example is 892 bytes of compact JSON, before
  lz4), irrelevant at these volumes.
- Nothing stops a producer at runtime from writing an event its schema rejects; the producer tests stop it before
  merge. A registry would reject it at publish time instead.
- Each consumer carries its own record types for the events it reads (ADR 0002); the schema examples keep them honest.
