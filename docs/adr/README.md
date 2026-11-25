# Architecture Decision Records

Short records of the decisions that shaped this system: the context, what was decided, and what it costs.
Format: [Michael Nygard's template](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).

| ADR | Decision | Status |
| --- | --- | --- |
| [0001](0001-database-per-service.md) | One database per service | Accepted |
| [0002](0002-independent-service-builds.md) | Independent service builds, no shared library | Accepted |
| [0003](0003-kafka-kraft-as-event-backbone.md) | Kafka in KRaft mode as the event backbone | Accepted, secured and in the default stack by 0022 (built in Phase 4) |
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
| [0016](0016-identity-provider-organisations-and-roles.md) | Identity provider: Keycloak, organisations and roles | Accepted |
| [0017](0017-authentication-at-the-gateway-and-in-every-service.md) | Authentication at the gateway and in every service: token relay, not trusted headers | Accepted |
| [0018](0018-object-level-authorisation.md) | Object-level authorisation and the policy table | Accepted, table revised by 0021 |
| [0019](0019-service-to-service-credentials.md) | Service-to-service credentials and secrets | Accepted |
| [0020](0020-caller-scoped-idempotency-and-audit-actors.md) | Caller-scoped idempotency keys and audit actors | Accepted |
| [0021](0021-four-eyes-approvals-for-operator-money-operations.md) | Four-eyes approvals for operator money operations | Accepted |
| [0022](0022-kafka-authentication-acls-and-topics.md) | Kafka authentication, ACLs and topic ownership | Accepted |
| [0023](0023-event-envelope-and-schema-evolution.md) | Event envelope, JSON Schema contracts and schema evolution | Accepted |
| [0024](0024-personal-data-stays-out-of-events.md) | Personal data stays out of events | Accepted |
| [0025](0025-transactional-outbox-relay.md) | Transactional outbox with a polling relay | Accepted |
| [0026](0026-consumer-idempotency-retries-and-ordering.md) | Consumers: idempotency, retries, dead letters and ordering | Accepted |
| [0027](0027-notification-service.md) | notification-service: emails in German and English, at most once | Accepted |
| [0028](0028-logistics-reacts-to-banking-hold-events.md) | logistics reacts to banking's hold events; payment commands stay HTTP | Accepted |
| [0029](0029-memory-budgets-and-jvm-sizing.md) | Memory budgets per container, and JVM sizing from each limit | Accepted |
| [0030](0030-opentelemetry-and-trace-propagation.md) | OpenTelemetry in every service, and one trace per booking through outboxes and Kafka | Accepted |
| [0031](0031-collector-sampling-and-redaction.md) | An OpenTelemetry Collector in front of the backends: tail sampling, redaction, infrastructure metrics | Accepted |
| [0032](0032-observability-stack.md) | The observability stack: separate Grafana, Prometheus, Tempo, Loki and Alertmanager behind a profile | Accepted |
| [0033](0033-slos-and-burn-rate-alerting.md) | SLOs on what customers feel, multi-window burn-rate alerts, and a fire drill | Accepted |
| [0034](0034-live-reference-exchange-rates.md) | Daily ECB reference rates, NAD under the CMA peg, a Bank of Namibia cross-check, and quarantine | Accepted |
| [0035](0035-duties-and-import-vat-estimate.md) | Duties and import VAT: an estimate from dated tariff subsets, with the EU-SADC EPA preference | Accepted |
| [0036](0036-duties-and-taxes-as-disbursements.md) | Duties and import VAT are disbursements in the ledger, never revenue | Accepted |
| [0037](0037-dcsa-track-and-trace.md) | Carrier tracking in DCSA Track and Trace 2.2: signed webhooks, a polling fallback, and a carrier simulator | Accepted |
| [0038](0038-namibian-duties-in-their-own-hold.md) | Duties into Namibia are collected in NAD, in a hold of their own | Accepted |
| [0039](0039-internal-ca-and-short-lived-certificates.md) | An internal CA with short-lived certificates, renewed by one agent | Accepted |
| [0040](0040-tls-on-every-hop-and-mutual-tls-between-services.md) | TLS on every hop, mutual TLS between the services | Accepted |
| [0041](0041-least-privilege.md) | Least privilege: database roles, containers, consoles | Accepted |
