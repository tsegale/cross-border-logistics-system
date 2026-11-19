# First requests after a cold start

What the first users of a freshly started stack wait for, before and after each service warms itself up before it
reports ready (Phase 4, §0.3).

## What the warm-up does

Every API request needs a token signed by Keycloak, and no service ever mints one, so the warm-up is not a replay of
real traffic. Each service runs the parts a first request pays for in a `WarmUpRunner` (an `ApplicationRunner`),
and Spring Boot publishes `ACCEPTING_TRAFFIC` only after every runner has returned:

| Service | Before it reports ready |
| --- | --- |
| gateway | probe token through the decoder (fetches Keycloak's keys), Redis round trip, each route's service through the gateway's own HTTP client, its own port without a valid token |
| banking | probe token (keys), one row of every JPA entity, its own port without a valid token |
| logistics | the same, plus its own client-credentials token and an FX rate from banking, and a sample quote priced through `QuoteService` in a transaction that is always rolled back |

The probe token always fails verification; fetching the keys to check it is the point. A failed step is logged and
the warm-up goes on, and the whole of it is bounded (`freightflow.warm-up.timeout`, 60 s): a slow dependency can
delay readiness, never prevent it. Logistics asks banking again each second until it answers, because logistics
starts without waiting for banking.

The compose healthchecks probe `/actuator/health/readiness`, so `docker compose up --wait`, the gateway's
`depends_on` and CI's "Default stack starts" job all wait for the warm-up. `WARM_UP_ENABLED=false` turns it off.

## Method

`benchmarks/first_requests.py`, straight after the services report ready and before anything else touches them:
six requests through the gateway, each once cold and then once more warm. Tokens are fetched before any timing, so
Keycloak's own cold start is not counted. Read-only requests plus one quote; nothing books or moves money.

Each sample recreates the three Java services (`docker compose up -d --wait --force-recreate banking-service
logistics-service api-gateway`) with the databases, Keycloak and Redis left running, as a redeploy does. Three
samples each way.

## Laptop (Intel Core i5-1135G7, 4 cores / 8 threads, 7.7 GB RAM, Windows 11, Docker Desktop 29.7.2)

First call after a cold start, three samples each:

| Request | Reaches | Before | After |
| --- | --- | ---: | ---: |
| `GET /api/v1/fx-rates?base=EUR` | banking | 1658 / 3408 / 2035 ms | 159 / 180 / 306 ms |
| `POST /api/v1/quotes` | logistics, banking | 1981 / 2155 / 4174 ms | 663 / 890 / 2596 ms |
| `GET /api/v1/shipments` | logistics | 104 / 128 / 552 ms | 153 / 178 / 481 ms |
| `GET /api/v1/approvals` | banking | 201 / 201 / 784 ms | 261 / 331 / 565 ms |
| `GET /api/v1/ledger/reconciliation` | banking | 83 / 98 / 198 ms | 90 / 138 / 190 ms |
| `GET /api/v1/payment-commands` | logistics | 51 / 68 / 155 ms | 74 / 137 / 160 ms |
| All six | | 4078 / 6058 / 7899 ms | 1400 / 1855 / 4297 ms |

The same requests warm took 199-462 ms for all six, in every sample.

- The first request to each service carried the cold start: 1.7-3.4 s for banking's, 2.0-4.2 s for the first quote.
  After the warm-up, banking's first request takes 159-306 ms and the first quote 663-2596 ms.
- The third "after" sample ran on a busier laptop: its warm pass was twice the others (437 ms against 199 and 218).
- Requests that came after the first one to a service were already partly warm before; they change little.
- The warm-up itself took 1.3-2.9 s per service. From `--force-recreate` to all three ready took 72-80 s.

None of these first calls reached the gateway's 15 s response timeout on this laptop. CI's "Default stack starts"
job runs the same script after `docker compose up --wait` on every push and writes its table to the job summary:
that is a full cold start, Keycloak and the databases included, on a hosted runner.
