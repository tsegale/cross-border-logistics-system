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

**Debezium beside the relay** (`benchmarks/compose.cdc.yml`, suite `cdc`): Debezium Server 3.2 with the outbox event
router reads logistics' `event_outbox` from the write-ahead log (`pgoutput`, its own replication slot) and publishes
each new row to a shadow topic, while the relay publishes the same rows as always. Per event, occurredAt to the
broker's append time on each topic (both set to `LogAppendTime`), 479 events, same load:

| Run (runner) | Debezium `poll.interval.ms` | Relay p50 / p95 / p99 ms | Debezium p50 / p95 / p99 ms | Broker down 30 s: last outage event after restart, relay / Debezium |
| --- | ---: | --- | --- | --- |
| 44839741944 (AMD EPYC 9V74, 4 vCPUs) | 500 (default) | 105 / 199 / 208 | 507 / 853 / 919 | 8.2 s / 11.7 s |
| 44841016554 (Xeon 6975P-C, 4 vCPUs) | 50 | 106 / 195 / 207 | 53 / 90 / 99 | 5.6 s / 8.6 s |

Debezium lost nothing in either run, during the outage included. On its defaults it is slower than the relay; tuned to
a 50 ms poll it is about 50 ms faster at p50 and 105 ms at p95, out of a booking-to-email p50 of about 360 ms. After
an outage it delivered the last waiting event 3 s later than the relay. Debezium Server used 265 to 275 MiB.

## Decision
**Option 1, a polling relay in each service.** Measured, CDC tuned to a 50 ms poll saves about 50 ms of a 360 ms
booking-to-email p50 and recovered 3 s slower from a broker outage; on its defaults it is slower than the relay. That
gain does not pay for a Debezium process per database, logical replication, a replication slot that retains WAL
while the connector is down, and one more thing whose outage stops all events. The relay can buy the same 50 ms by
polling every 50 ms instead of 200, at the price of four times the idle queries.

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
  database would each need a relay. The outbox table works with Debezium's outbox router as it is (route by `topic`,
  key `aggregate_id`, payload `envelope`; `benchmarks/cdc/start-debezium.sh`).
