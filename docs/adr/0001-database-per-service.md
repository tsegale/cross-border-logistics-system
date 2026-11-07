# 1. One database per service

Date: 2026-11-07 · Status: Accepted

## Context
banking-service and logistics-service change for different reasons and at different rates. The ledger needs
strict transactional guarantees and an append-only history; shipments need a state machine and a tracking
log. If both read and write one shared schema, a migration in one can break the other, neither can be
deployed alone, and nothing stops logistics from "just reading" a balance and acting on a stale value.

## Decision
- Each service owns one PostgreSQL database, created and migrated only by its own Flyway migrations.
- No service connects to another service's database, not even read-only.
- Data crosses a service boundary only through the owning service's API (synchronous) or its events
  (asynchronous, Phase 4).
- In compose each database is its own container with its own credentials, so a misconfigured URL fails
  instead of silently reaching the wrong schema.

## Consequences
- No cross-service joins or foreign keys. logistics-service stores the account *number* it charges, not a
  foreign key into the ledger, and has to cope with the banking service being slow or down (Phase 2).
- A shipment payment spans two databases, so it can't be one ACID transaction. It needs an idempotent,
  retry-safe protocol between the services (Phase 2) and an outbox for events (Phase 4).
- More containers locally. Acceptable: two small Postgres instances are cheap.
