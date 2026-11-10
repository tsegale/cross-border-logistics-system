# FreightFlow: Cross-Border Logistics System

A microservices backend for a freight forwarder on the Walvis Bay (`NAWVB`, Namibia) to Hamburg (`DEHAM`, Germany) corridor.
Customers get freight quotes, book shipments and follow them through ports and customs, and every charge
settles through an internal double-entry ledger with accounts in `NAD`, `ZAR` and `EUR`.

Java 21, Spring Boot 4.1, Spring Cloud Gateway, PostgreSQL 17, Kafka, Docker. Built in phases; each phase ships
with tests, CI, architecture decision records and measured numbers.

> **Status:** Phase 1 complete — double-entry multi-currency ledger, idempotent transfers & FX quotes (`banking-service`), and freight quotes, shipment state machine & out-of-order tracking (`logistics-service`).

## Architecture

```
                    ┌──────────────────────────┐
  client ──HTTP──▶  │ api-gateway        :8080 │   routes /api/v1/**, /docs/banking/**, /docs/logistics/**
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

## Engineering highlights (Phase 1)

- **Append-only double-entry ledger ([ADR 0004](docs/adr/0004-double-entry-append-only-ledger.md))**: Every balance change is recorded as a balanced journal entry (`SUM(postings) = 0` per currency) enforced by a `DEFERRABLE INITIALLY DEFERRED` PostgreSQL constraint trigger (`trg_verify_journal_entry_balanced`). `BEFORE UPDATE OR DELETE` triggers block in-place mutations on `journal_entries` and `postings`; corrections happen only via one-time compensating `REVERSAL` entries.
- **Deadlock-free concurrent transfers ([ADR 0005](docs/adr/0005-transfer-concurrency-strategy.md))**: Participating accounts are locked in ascending UUIDv7 order (`SELECT ... WHERE id IN (:ids) ORDER BY id ASC FOR UPDATE`) after evicting pre-lock JPA 1st-level cache snapshots. Verified under 200 concurrent drains on 1 account, 100 concurrent `A -> B` / `B -> A` pairs (0 `40P01` deadlocks), and 20 concurrent identical `Idempotency-Key` requests.
- **Transactional idempotency keys ([ADR 0006](docs/adr/0006-idempotency-keys.md))**: Implements `draft-ietf-httpapi-idempotency-key-header-08` using `pg_advisory_xact_lock(hashtext(scope), hashtext(key))` and canonical SHA-256 request hashes stored in the same ACID transaction (`422` on payload mismatch, `409` + `Retry-After: 1` when in flight, `Idempotent-Replayed: true` on replay).
- **Exact decimal money, CMA peg & 4-posting FX transfers ([ADR 0007](docs/adr/0007-money-rounding-and-fx-quotes.md))**: `NUMERIC(19,4)` amounts, `NUMERIC(19,8)` FX rates, `RoundingMode.HALF_EVEN` to ISO 4217 minor units, 1:1 `NAD`/`ZAR` Common Monetary Area peg, and locked FX quotes (`POST /api/v1/fx-quotes`) settled via 4 postings through `FX_POSITION` accounts so every currency balances to zero.
- **Industry chargeable weight & Incoterms 2020 ([ADR 0008](docs/adr/0008-freight-quotes-and-chargeable-weight.md))**: `AIR` volumetric weight ($\text{cm}^3 / 6000$, IATA Resolution 502) and `SEA_LCL` revenue ton ($\max(\text{t}, \text{m}^3)$ W/M) with immutable tariff snapshotting and `EXW`/`FCA`/`DAP`/`DDP` responsibility allocation.
- **Explicit shipment state machine, out-of-order tracking & `ETag`/`If-Match` ([ADR 0009](docs/adr/0009-shipment-state-machine-and-tracking.md))**: Crockford Base32 tracking numbers (`FF-<8 chars>-<check>`) with ISO 7064 MOD 37-2 check character; late/out-of-order carrier scans (`occurredAt < latestEventAt`) are recorded in history with `appliedToStatus = false` without reverting shipment status; public tracking converts timestamps into each location's IANA time zone (`Africa/Windhoek`, `Europe/Berlin`) and strips customer PII.
- **Keyset pagination verified with `EXPLAIN (ANALYZE, BUFFERS)`**: Statement pagination reads 4 buffer pages in `0.110 ms` at `90,688` postings; shipment listing reads 7 buffer pages in `0.163 ms` at `20,000` shipments.
- **RFC 9457 Problem Details everywhere**: Every validation and domain error returns `application/problem+json` with a stable `type` URI documented in [`docs/problems.md`](docs/problems.md).

## Tech stack

| Area | Technology |
| --- | --- |
| Language, framework | Java 21, Spring Boot 4.1 |
| Edge | Spring Cloud Gateway 5 (WebFlux) |
| Persistence | PostgreSQL 17, Spring Data JPA, Flyway |
| Messaging | Apache Kafka 4 in KRaft mode (Phase 4) |
| API docs | springdoc-openapi, Swagger UI (`/docs/banking/swagger-ui.html`, `/docs/logistics/swagger-ui.html`) |
| Tests | JUnit 5, Mockito, Testcontainers, JaCoCo (`>= 80%` line / `>= 70%` branch gate), k6 |
| Delivery | Docker multi-stage layered images, Docker Compose, GitHub Actions |
| Supply chain | gitleaks, Trivy image scan, SPDX SBOM, Dependabot |

## Quickstart

Needs Docker and JDK 21 (only for running tests outside Docker). No local Maven: every service has a wrapper.

```bash
cp .env.example .env              # then change the passwords
docker compose up -d --build --wait
curl http://127.0.0.1:8080/actuator/health
./scripts/seed_demo.sh            # seeds NAD/ZAR/EUR accounts, FX transfer & shipment
```

Interactive OpenAPI docs through the gateway:
- **Banking API**: `http://127.0.0.1:8080/docs/banking/swagger-ui.html`
- **Logistics API**: `http://127.0.0.1:8080/docs/logistics/swagger-ui.html`
- **Full curl walkthrough**: [`docs/smoke-test.md`](docs/smoke-test.md)

## Phase 1 API examples (via `:8080`)

### 1. Open an account, deposit funds & execute a cross-currency FX transfer

```bash
# Open a NAD exporter account (ISO 7064 MOD 97-10 check digits)
curl -s -X POST http://127.0.0.1:8080/api/v1/accounts \
  -H "Content-Type: application/json" \
  -d '{"holderReference":"Namib Fisheries (Pty) Ltd","currency":"NAD"}'
```

```json
{
  "id": "01a1ba5f-f074-7e31-a07e-7fb5fead5078",
  "accountNumber": "FF20NAD000001401",
  "holderReference": "Namib Fisheries (Pty) Ltd",
  "type": "CUSTOMER",
  "currency": "NAD",
  "status": "ACTIVE",
  "balance": 0.00
}
```

```bash
# Lock a 5-minute FX quote (50,000.00 NAD -> EUR)
curl -s -X POST http://127.0.0.1:8080/api/v1/fx-quotes \
  -H "Content-Type: application/json" \
  -d '{"sellCurrency":"NAD","buyCurrency":"EUR","sellAmount":50000.00}'
```

```json
{
  "id": "01a1ba60-0961-7a53-8519-ec54986c624b",
  "sellCurrency": "NAD",
  "buyCurrency": "EUR",
  "sellAmount": 50000.00,
  "buyAmount": 2487.50,
  "midRate": 0.05000000,
  "spread": 0.005000,
  "rate": 0.04975000,
  "consumed": false
}
```

### 2. Quote ocean freight (`NAWVB -> DEHAM`), book a shipment & track across time zones

```bash
# Quote 2 pallets of frozen hake (120x100x150 cm, 500 kg each -> 3.6000 CBM / 1.0 t -> 3.6 WM_TON)
curl -s -X POST http://127.0.0.1:8080/api/v1/quotes \
  -H "Content-Type: application/json" \
  -d '{
    "origin": "NAWVB",
    "destination": "DEHAM",
    "mode": "SEA_LCL",
    "incoterm": "FCA",
    "payerRole": "CONSIGNEE",
    "packages": [{"lengthCm":120,"widthCm":100,"heightCm":150,"weightKg":500,"quantity":2,"description":"Frozen hake fillets"}],
    "currency": "EUR"
  }'
```

```json
{
  "id": "01a1ba60-1fc7-711b-b006-c4e7c35d8af5",
  "origin": "NAWVB",
  "destination": "DEHAM",
  "mode": "SEA_LCL",
  "incoterm": "FCA",
  "payerRole": "CONSIGNEE",
  "billingUnit": "WM_TON",
  "actualWeightKg": 1000.000,
  "volumeCbm": 3.6000,
  "chargeableWeightKg": 3600.000,
  "chargeableUnits": 3.6000,
  "currency": "EUR",
  "baseFreightCharge": 342.00,
  "fuelSurcharge": 42.75,
  "destinationHandlingCharge": 85.00,
  "importClearanceCharge": 55.00,
  "totalAmount": 524.75
}
```

```bash
# Public tracking by Crockford Base32 tracking number (local IANA time zones, zero customer PII)
curl -s http://127.0.0.1:8080/api/v1/shipments/track/FF-00010000-6
```

## Transfer contention benchmark

Measured on `Intel64 Family 6 Model 140 Stepping 1, GenuineIntel` (8 logical cores), Windows 11 (`10.0.26300`), Docker Desktop / WSL 2, PostgreSQL 17, Eclipse Temurin 21, HikariCP `maximum-pool-size = 32` (3 interleaved rounds of 1,000 requests per strategy after JVM warm-up; full report in [`docs/benchmarks/transfer-contention.md`](docs/benchmarks/transfer-contention.md)):

| Workload | Concurrency | Strategy | Median `req/s` | `p50` (`ms`) | `p95` (`ms`) | `p99` (`ms`) | Reconciled |
| --- | :---: | --- | ---: | ---: | ---: | ---: | :---: |
| **Hot Account** (1 source account) | 32 VUs | `pessimistic` | **31.01** | **162.02** | **5,492.06** | **8,797.34** | `true` (0.0000 drift) |
| **Hot Account** (1 source account) | 32 VUs | `conditional-update` | 25.10 | 177.72 | 5,937.42 | 11,083.65 | `true` (0.0000 drift) |
| **Many Accounts** (disjoint pairs) | 32 VUs | `pessimistic` | **76.58** | **300.03** | **1,084.06** | **1,836.00** | `true` (0.0000 drift) |
| **Many Accounts** (disjoint pairs) | 32 VUs | `conditional-update` | 67.75 | 308.53 | 1,230.75 | 2,336.80 | `true` (0.0000 drift) |
| **Hot Account** (1 source account) | 100 VUs | `pessimistic` | **30.91** | **2,134.43** | **7,076.93** | **12,621.20** | `true` (0.0000 drift) |
| **Hot Account** (1 source account) | 100 VUs | `conditional-update` | 27.83 | 2,183.31 | 8,088.21 | 13,388.12 | `true` (0.0000 drift) |
| **Many Accounts** (100 accounts) | 100 VUs | `pessimistic` | 60.77 | **1,145.84** | 3,117.91 | 3,751.96 | `true` (0.0000 drift) |
| **Many Accounts** (100 accounts) | 100 VUs | `conditional-update` | **72.07** | 1,152.38 | **2,473.06** | **3,104.94** | `true` (0.0000 drift) |

## Running the tests

```bash
cd banking-service && ./mvnw verify       # banking-service (48 unit + Testcontainers + concurrency tests)
cd logistics-service && ./mvnw verify     # logistics-service (40 unit + Testcontainers tests)
```

Integration tests start their own PostgreSQL 17 in Testcontainers, so Docker must be running. Every service enforces a JaCoCo coverage gate (`>= 80%` line, `>= 70%` branch).

## Roadmap

| Phase | Scope | Status |
| --- | --- | --- |
| 0 | Scaffold: standalone services, compose, CI with scans and SBOMs, first ADRs | Done |
| 1 | Ledger and shipments core: double-entry ledger, idempotent transfers, FX quotes, freight quotes, shipment state machine | Done |
| 2 | Settlement across services: gateway routes, HTTP clients with resilience, idempotent payment for shipments | Next |
| 3 | Security: Keycloak, OAuth2 resource server at the gateway, roles, identity headers | Planned |
| 4 | Events: transactional outbox, Kafka, notification service, dead-letter topics, contract tests | Planned |
| 5 | Observability: OpenTelemetry traces, metrics and logs, Grafana dashboards, SLOs and alerts | Planned |
| 6 | Real-world data: live exchange rates, customs duties and Incoterms, carrier tracking feeds | Planned |
| 7 | Hardening: load and chaos tests, threat model, cloud deployment | Planned |

## Repository layout

```
api-gateway/            Spring Cloud Gateway (:8080)
banking-service/        accounts, double-entry ledger, transfers, FX quotes (:8081)
logistics-service/      freight quotes, shipments, tracking events (:8082)
notification-service/   event consumer (:8083, Phase 4)
benchmarks/             k6 and Python transfer-contention benchmark harnesses
scripts/seed_demo.sh    end-to-end demo data seeder through the gateway
docs/adr/               architecture decision records (0001–0009)
docs/benchmarks/        measured benchmark reports
docs/problems.md        RFC 9457 Problem Details type URI registry
docs/smoke-test.md      end-to-end gateway curl walkthrough
.github/                CI and Dependabot
```

## Engineering conventions

The reasons behind the big decisions are in [docs/adr](docs/adr/README.md).

Built by Parata Kativa.
