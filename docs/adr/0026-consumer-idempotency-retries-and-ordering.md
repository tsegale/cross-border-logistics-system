# 26. Consumers: idempotency, retries, dead letters and ordering

Date: 2026-11-20 · Status: Accepted

## Context
Events arrive at least once (ADR 0025): a relay that died after the broker's acknowledgement sends the event again,
a consumer that died before committing its offset reads it again, a rebalance hands a partition over mid-batch, and an
operator may replay a topic on purpose. A consumer that acts twice sends a customer two emails or re-authorises a hold
twice.

Consumers also fail: a database is briefly down, an event names a shipment the consumer has not heard of yet, or a
record is not an event at all. A failure must neither lose the event nor stop the partition behind it forever.

And they rely on order: a shipment's events are ordered on its partition (ADR 0023), but a retry can put an old event
behind newer ones.

## Options
**Idempotency**
1. **Natural idempotency only** (upserts, "set status to X"). Works for state, not for side effects such as an email.
2. **Processed-events table keyed by `eventId`**, written in the same database transaction as the effect.
3. **Kafka transactions (exactly-once read-process-write).** Covers Kafka-to-Kafka; an email or a database row outside
   Kafka is still a side effect the transaction does not cover.

**Retries**
1. **Blocking retries in the container** (`DefaultErrorHandler` with backoff): the partition waits, order holds.
2. **Non-blocking retry topics** (Spring Kafka `@RetryableTopic`): the failing record moves to `-retry-<n>` topics with
   growing delays, the main partition keeps going, the record comes back after newer ones of its key.

## Decision
**Idempotency: option 2 in every consumer.** `processed_events (event_id PK, topic, event_type, processed_at)`. The
handler runs in one transaction: insert the id with `ON CONFLICT DO NOTHING`; if nothing was inserted, the event was
handled before and the handler returns; otherwise it acts and commits both. A second consumer of the same event (a
rebalance) waits on the primary key until the first commits, then finds it. Rows are pruned after 35 days, longer than
the topics' 30-day retention, so no event older than a pruned row can be delivered again. Offsets are committed per
record (`ack-mode: record`) after the handler returns.

**Retries: chosen per consumer, for what it reads.**

| | logistics (`hold.v1`) | notification-service (`shipment.v1`, `hold.v1`) |
| --- | --- | --- |
| What fails | the database | the database; a hold event for a shipment whose booking it has not consumed yet (another topic, no order between them) |
| Retry | blocking, 200 ms doubling to 5 s, about 20 s | non-blocking: `-retry-0..2` after 1 s, 5 s, 25 s |
| Why | two event types acted on, rare; order matters for a hold's lifecycle; blocking 20 s costs nothing | many events; one customer's slow case must not delay every other customer's email |
| Then | `freightflow.hold.v1.logistics-service.dlt` | `<topic>.notification-service-dlt`, stored in `dead_letters` |

Spring Kafka 4.1's `@RetryableTopic` works on Boot 4 (verified in `DeadLetterIntegrationTest`: three retries, then the
dead-letter topic). Two details found on the way: the retry consumers' group ids are the main group id plus the topic
suffix, so the ACLs grant notification the group *prefix* `notification-service` (`infra/kafka/acls.conf` takes `*`
as Kafka's prefixed pattern); and records that went through retry topics carry the failure in `kafka_exception-*`
headers, not `kafka_dlt-*`.

**Poison messages** (not JSON, no `eventId`, `type` or `sequence`) throw `PoisonEventException`, excluded from retries:
they go to the dead-letter topic at once.

**Ordering with retry topics: the envelope's `sequence`.** notification-service keeps `shipments.last_sequence` per
shipment and applies a status event only if its sequence is higher (an upsert with `WHERE last_sequence < excluded`).
A status change that was retried and comes back after a later one is stale: no projection change, no email for an
outdated status (`anOlderStatusThatArrivesLateIsDroppedAsStale`). Events that are facts rather than states (a booking,
a payment failure, a release) are not dropped by sequence; their idempotency is the event id. logistics keeps order by
blocking, and acts only on terminal hold events, so it needs no sequence check.

**Dead letters, operated.** notification-service's `@DltHandler` stores each dead letter (topic, partition, offset,
original topic, value, event id and type, error) in `dead_letters`, idempotent on (topic, partition, offset).
Operators list them (`GET /api/v1/dead-letters`, auditors too) and redrive one
(`POST /api/v1/dead-letters/{id}/redrive`): the stored record goes through the same handler in one transaction with the
status change, and the operator is recorded as `redriven_by` (ADR 0020). A redrive that fails again is a 409
`dead-letter-redrive-failed`, counted, and the letter stays `NEW`. It needs no second operator: it repeats what the
consumer would have done and moves no money (ADR 0021).

logistics does the same at `/api/v1/logistics/dead-letters` (the gateway routes `/api/v1/dead-letters` to
notification-service). Its error handler writes the record to its own `dead_letters` table, idempotent on the original
topic, partition and offset, before publishing it to `freightflow.hold.v1.logistics-service.dlt`: if either step fails
the handler recovers the record again, and the table ignores the second copy. A redrive hands the stored event to
`BankingHoldEvents`, the consumer's handler, so a re-authorisation it causes is a payment command like any other
(ADR 0012) and an event that was handled in the meantime changes nothing. The dead-letter topic stays, as the copy
on the broker.

## Consequences
- An event is acted on at most once per consumer, whatever the broker or the relay does; tests replay events by hand
  (`aReplayedEventSendsNoSecondEmail`, `aRedeliveredExpiryChangesNothingTheSecondTime`, and the CI smoke test).
- Every consumer pays one insert per event and a 35-day table.
- notification-service's retry topics are six more topics and consumer groups, created by Spring Kafka at startup
  (`autoCreateTopics`) under its ACL prefix.
- After a failure, a notification-service event can arrive about half a minute late and out of order; an email about a
  state is never sent for an outdated one.
