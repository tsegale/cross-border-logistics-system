# Architecture Decision Records

Short records of the decisions that shaped this system: the context, what was decided, and what it costs.
Format: [Michael Nygard's template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-database-per-service.md) | One database per service | Accepted |
| [0002](0002-independent-service-builds.md) | Independent service builds, no shared library | Accepted |
| [0003](0003-kafka-kraft-as-event-backbone.md) | Kafka in KRaft mode as the event backbone | Accepted (built in Phase 4) |
