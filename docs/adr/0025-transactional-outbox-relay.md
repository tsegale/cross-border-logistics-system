# 25. Transactional outbox with a polling relay

Date: 2026-11-20 · Status: Accepted

## Context
A service that changes its database and then publishes to Kafka can fail between the two: the change committed and
the event is lost, or the event went out for a change that rolled back. Consumers would then act on something that
did not happen, or never hear of something that did (a released hold, a booking). Writing to Kafka inside the
database transaction is not possible; a distributed transaction (XA) between Postgres and Kafka is not offered.

The requirements (Phase 4 brief, §3): the event is written in the same transaction as the change; publishing uses an
idempotent producer with `acks=all`; no two events of one aggregate are in flight out of order, also across relay
instances; a row is marked published only after the broker acknowledged it; published rows are pruned; logistics'
payment-command outbox and its new event outbox share one mechanism.

## Options
1. **Polling relay.** The service writes `event_outbox` rows in the change's transaction; a scheduled relay in the same
   service claims due rows, sends them, marks them. No new infrastructure; latency is the poll interval plus the send.
2. **Log-based change data capture (Debezium).** Debezium reads Postgres' write-ahead log through a replication slot
   and publishes the outbox rows (its outbox event router), usually on Kafka Connect. Lowest latency, no polling
   queries, and the service needs no relay code. Costs: a Kafka Connect cluster (a JVM per node, its own config, offset
   and status topics) or Debezium Server per database; logical replication (`wal_level=logical`) and a replication
   slot per database, which retains WAL while the connector is down and can fill the disk; one more component whose
   outage stops all events; schema and ACL setup for Connect's own topics.

## Measurements
Hosted runner (GitHub `ubuntu-24.04`, 4 vCPUs), runs 44744962828 and 44750794366 (`benchmarks/events_to_email.py`,
[docs/benchmarks/events.md](../benchmarks/events.md)), 2 bookings per second for 120 s:

| | Measured |
| --- | --- |
| Outbox lag, commit to broker ack, logistics (480 events) | p50 at most 112 ms, p95 at most 201 ms, p99 at most 224 ms |
| Outbox lag, banking (240 events) | p50 at most 134 ms, p95 at most 224 ms, p99 at most 224 ms |
| Booking to email delivered | p50 361 / 368 ms, p95 612 / 618 ms, p99 674 / 658 ms; 0 of 480 missing |
| Broker stopped for 30 s under load: restart to both outboxes empty | 6.1 s and 7.7 s (186 and 187 events waiting) |
| Same outage: restart to the last email of the outage | 6.6 s and 9.9 s; 0 of 160 missing |

Debezium was not set up and measured here, so there is no CDC number to put beside these. What CDC could save is
bounded by what polling costs: the wait for the next poll, at most the 200 ms interval and about half of it on
average, against a booking-to-email p50 of about 360 ms. Recovery after a broker outage depends on the connector's
own retry and was not measured either.

## Decision
**Option 1, a polling relay in each service**, because its measured lag is at most 112 to 134 ms at p50, mostly the poll
interval, and recovery after a broker outage is seconds with nothing lost, while CDC would add a
Connect cluster, logical replication and a replication slot per database to operate.

- **Writing.** `BankingEvents` and `LogisticsEvents` append to `event_outbox` with `Propagation.MANDATORY`: there is no
  way to write an event outside a transaction. The aggregate's next `sequence` comes from an upsert on
  `event_sequences`, whose row lock orders concurrent writers of one aggregate. Every path flushes the aggregate's own
  row before appending, so the aggregate lock is always taken before the sequence lock (no deadlock between a tracking
  event and a payment outcome on one shipment).
- **Claiming** (logistics' `OutboxClaims`, shared with the payment commands; banking's `EventOutbox.claim`): one
  `UPDATE ... WHERE id IN (SELECT ... FOR UPDATE SKIP LOCKED) RETURNING`, leasing rows by moving `next_attempt_at` out
  60 s. Only an aggregate's oldest unpublished event is claimable, so while it is in flight no relay on any instance
  can take the next one: per-aggregate order without a leader or partition assignment.
- **Sending.** Kafka producer with `acks=all`, `enable.idempotence=true`, `delivery.timeout.ms` 25 s within the relay's
  30 s wait, within the 60 s lease. Key: aggregate id. Headers: event id, type, schema version, aggregate id,
  sequence. A row is marked `published_at` only for an acknowledged send; a failed one is retried after an exponential
  backoff with jitter (1 s to 1 min); the rest of its aggregate waits behind it. A round runs again at once while it
  publishes, up to 20 rounds per tick, so one aggregate's burst drains without waiting for the next tick (200 ms).
- **Topics.** If `KafkaAdmin` could not declare the topics at startup (broker down), the relay declares them before
  its first send, and again after a send fails for a missing topic.
- **Pruning.** Published rows are deleted after 7 days (`OutboxPruner`, in batches); unpublished rows never are.
  `event_sequences` is never pruned, so a sequence does not restart.
- **At least once, never out of order.** A relay that dies after the acknowledgement and before marking leaves the row
  unpublished; once its lease runs out another relay sends it again. Consumers drop the copy by `eventId` (ADR 0026).
  A broker that wrote a batch the client had given up on also produces a copy; it is the same event, before the next
  sequence.

## Consequences
- Proven against a real broker in both services (`EventRelayIntegrationTest`): a paused broker (events wait, nothing
  is lost, order holds once it is back), topics missing at startup, a relay that dies before marking (a duplicate, no
  gap), four relays racing on 64 events of four aggregates (each in order), pruning.
- Each service polls its outbox every 200 ms: one indexed query on a partial index when idle.
- Outbox backlog, age and lag are metrics with alerts (docs/operations.md).
- Revisit with CDC when event volume makes polling the database's main load, or when several services in one
  database would each need a relay; the outbox table is already Debezium's outbox router format in all but column
  names.
