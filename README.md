# FreightFlow: Cross-Border Logistics System

A microservices backend for a freight forwarder on the Walvis Bay (Namibia) to Hamburg (Germany) corridor.
Customers get freight quotes, book shipments and follow them through ports and customs, and every charge
settles through an internal double-entry ledger with accounts in NAD, ZAR and EUR.

Java 21, Spring Boot 4.1, Spring Cloud Gateway, PostgreSQL, Kafka, Docker. Built in phases; each phase ships
with tests, CI, architecture decision records and measured numbers.

> **Status:** Phase 0, the scaffold. The services start and report healthy; the domain is built from Phase 1.

## Architecture

```
                    ┌──────────────────────────┐
  client ──HTTP──▶  │ api-gateway        :8080 │   routing now; auth, rate limits, identity headers later
                    └─────┬───────────────┬────┘
                          │               │
             ┌────────────▼───┐       ┌───▼──────────────┐
             │ banking  :8081 │◀──────│ logistics  :8082 │   logistics charges freight to the ledger (Phase 2)
             │ ledger, FX     │       │ quotes, tracking │
             └───────┬────────┘       └────────┬─────────┘
                     │                         │
              ┌──────▼──────┐           ┌──────▼──────┐
              │ banking-db  │           │ logistics-db│     one PostgreSQL per service (ADR 0001)
              └─────────────┘           └─────────────┘
                     │   outbox → Kafka (KRaft) → notification-service :8083   (Phase 4)
```

## Tech stack

| Area | Technology |
| --- | --- |
| Language, framework | Java 21, Spring Boot 4.1 |
| Edge | Spring Cloud Gateway 5 (WebFlux) |
| Persistence | PostgreSQL 17, Spring Data JPA, Flyway |
| Messaging | Apache Kafka 4 in KRaft mode (Phase 4) |
| API docs | springdoc-openapi, Swagger UI |
| Tests | JUnit 5, Mockito, Testcontainers, k6 for load |
| Delivery | Docker multi-stage layered images, Docker Compose, GitHub Actions |
| Supply chain | gitleaks, Trivy image scan, SPDX SBOM, Dependabot |

## Quickstart

Needs Docker and JDK 21 (only for running tests outside Docker). No local Maven: every service has a wrapper.

```bash
cp .env.example .env              # then change the passwords
docker compose up --build --wait
curl http://127.0.0.1:8080/actuator/health/readiness
```

Optional pieces: `docker compose --profile events up` adds Kafka; `--profile notifications` adds the
notification service.

## Running the tests

```bash
cd banking-service && ./mvnw verify       # one service (mvnw.cmd on Windows cmd)
banking-service/mvnw -f pom.xml verify    # every service
```

Integration tests start their own PostgreSQL in Testcontainers, so Docker must be running.

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Scaffold: standalone services, compose, CI with scans and SBOMs, first ADRs | Done |
| 1 | Ledger and shipments core: double-entry ledger, idempotent transfers, FX quotes, freight quotes, shipment state machine | Next |
| 2 | Settlement across services: gateway routes, HTTP clients with resilience, idempotent payment for shipments | Planned |
| 3 | Security: Keycloak, OAuth2 resource server at the gateway, roles, identity headers | Planned |
| 4 | Events: transactional outbox, Kafka, notification service, dead-letter topics, contract tests | Planned |
| 5 | Observability: OpenTelemetry traces, metrics and logs, Grafana dashboards, SLOs and alerts | Planned |
| 6 | Real-world data: live exchange rates, customs duties and Incoterms, carrier tracking feeds | Planned |
| 7 | Hardening: load and chaos tests, threat model, cloud deployment | Planned |

## Repository layout

```
api-gateway/            Spring Cloud Gateway
banking-service/        accounts, ledger, FX quotes
logistics-service/      quotes, shipments, tracking
notification-service/   event consumer (Phase 4)
docs/adr/               architecture decision records
docs/dev-setup.md       Windows development setup
.github/                CI and Dependabot
```

## Engineering conventions

The reasons behind the big decisions are in [docs/adr](docs/adr/README.md).

Built by Parata Kativa.
