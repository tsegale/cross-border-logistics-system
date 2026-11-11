# Architecture Decision Records

Short records of the decisions that shaped this system: the context, what was decided, and what it costs.
Format: [Michael Nygard's template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-database-per-service.md) | One database per service | Accepted |
| [0002](0002-independent-service-builds.md) | Independent service builds, no shared library | Accepted |
| [0003](0003-kafka-kraft-as-event-backbone.md) | Kafka in KRaft mode as the event backbone | Accepted (built in Phase 4) |
| [0004](0004-double-entry-append-only-ledger.md) | Double-entry, append-only ledger with database-enforced invariants | Accepted |
| [0005](0005-transfer-concurrency-strategy.md) | Transfer concurrency control: ordered pessimistic locking vs conditional UPDATE | Accepted |
| [0006](0006-idempotency-keys.md) | Transactional idempotency keys with advisory locks | Accepted |
| [0007](0007-money-rounding-and-fx-quotes.md) | Exact decimal money, HALF_EVEN rounding, and locked FX quotes | Accepted |
| [0008](0008-freight-quotes-and-chargeable-weight.md) | Freight quotes, industry chargeable weight, and Incoterms 2020 allocation | Accepted |
| [0009](0009-shipment-state-machine-and-tracking.md) | Shipment state machine, out-of-order tracking events, and optimistic locking | Accepted |
| [0010](0010-unlocked-system-accounts.md) | System accounts are posted to without a row lock | Accepted |
| [0011](0011-holds-authorise-capture-release.md) | Holds: authorise, capture, release | Accepted |
| [0012](0012-shipment-payment-saga.md) | Paying for a shipment: a saga with unknown outcomes | Accepted |
| [0013](0013-http-client-and-resilience-policy.md) | HTTP client and resilience policy for calls to banking-service | Accepted |
| [0014](0014-api-contract-tests.md) | API contract tests: a committed OpenAPI document on both sides | Accepted |
| [0015](0015-gateway-edge-policy.md) | Gateway edge policy | Accepted |
