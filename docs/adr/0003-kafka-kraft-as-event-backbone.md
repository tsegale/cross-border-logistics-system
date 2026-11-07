# 3. Kafka in KRaft mode as the event backbone

Date: 2026-11-07 · Status: Accepted (built in Phase 4)

## Context
Several things react to what the banking and logistics services do: customer notifications, an audit trail
of money movements, and later read models such as a customer's shipment and payment history. They need the
events of one account or one shipment in order, and a new consumer should be able to rebuild its view from
history instead of starting empty.

## Options
1. **RabbitMQ topic exchange** (what Smart Tours, the owner's previous project, uses): flexible routing,
   per-message acknowledgement. Messages are gone once consumed, so a new consumer can't replay history.
2. **Apache Kafka**: a partitioned, replicated log. Ordering per key within a partition, retention by time,
   consumer groups that each keep their own offset, so a consumer can be reset and replay.
3. **PostgreSQL LISTEN/NOTIFY or polling tables**: no new infrastructure, but no durable fan-out, and it
   couples consumers to the producer's database (against ADR 0001).

## Decision
Kafka, with the aggregate id as the message key so all events of one account or shipment land on one
partition in order. Topics are named `freightflow.<aggregate>.v<n>` and are created by the owning service,
never auto-created (`auto.create.topics.enable=false`).

Kafka 4 runs in KRaft mode: the brokers manage their own metadata quorum. ZooKeeper support was removed in
Kafka 4.0, so the scaffold's ZooKeeper container is gone.

## Consequences
- A database commit and a Kafka send can't be one transaction. Producers write events to a transactional
  outbox table in their own database and a relay publishes them (Phase 4).
- Delivery is at-least-once. Every consumer is idempotent (it records processed `eventId`s) and has a
  dead-letter topic for messages it can't process.
- Locally Kafka runs as one combined broker and controller node, behind `--profile events`, so the default
  stack stays small until the services publish events.
