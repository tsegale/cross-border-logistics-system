# 15. Gateway edge policy

Date: 2026-11-11 · Status: Accepted

## Context
In Phase 1 the gateway only routed paths. Phase 2 adds internal endpoints (banking's holds, logistics' payment
commands) that must not be reachable from outside, and service-to-service calls whose failures are now visible to
customers. The edge has to bound what it accepts and how long it waits, retry only what is safe to retry, give
every request an id that can be followed through the logs, and answer its own errors in the same RFC 9457 format
as the services. Smart Tours (the owner's previous project) did this with a `GatewayProblemHandler`; the same
pattern fits here.

## Decision
- **Internal paths answer 404 at the edge.** `/api/v1/holds/**`, `/api/v1/payment-commands/**` and `/actuator/**`
  are refused by `InternalPathFilter`, the first global filter, with a `404 not-found` problem, whatever routes
  exist. Nothing routes them either: the filter is the second line of defence against a future wildcard route.
  The gateway's own actuator moves to a management port (`8085`, published on `127.0.0.1` only) for health checks
  and Prometheus. Phase 3 replaces "unreachable" with service-to-service credentials.
- **Timeouts.** Connect 2 s. Response timeout 15 s per route: a money-moving request may wait up to the ledger's
  10 s account-lock timeout (ADR 0006) before banking answers `503 lock-timeout`, and the gateway must not cut that
  answer off; 15 s leaves room for the transaction and the response. A route that times out is a `504
  upstream-timeout` problem.
- **Request size.** 1 MB, enforced on `Content-Length` and on the bytes actually read for chunked bodies, answered
  `413 request-too-large`. The largest real request (a quote with many package lines) is a few kilobytes.
- **Retries only for idempotent reads.** `GET` and `HEAD` are retried twice on connection errors, `502`, `503` and
  `504`, with exponential backoff from 50 ms to 500 ms. A `POST` is never retried at the edge, even with an
  `Idempotency-Key`: the client owns that retry, and the edge cannot know whether the first attempt committed.
- **Request ids.** `X-Request-Id` is kept if the client sent a valid one (1-64 of `[A-Za-z0-9._-]`) and generated
  (UUID) otherwise. It is forwarded upstream, returned on the response, and put in the log context. banking and
  logistics read it into their MDC (it appears as `requestId` in their ECS JSON logs) and logistics forwards it on
  its calls to banking. Full tracing is Phase 5.
- **Problem details for gateway errors.** `GatewayProblemHandler` (an `ErrorWebExceptionHandler`) turns every error
  the gateway itself produces into `application/problem+json`: `404 not-found` (no route), `413
  request-too-large`, `502 upstream-error`, `503 upstream-unavailable` (connection refused, unknown host),
  `504 upstream-timeout`, anything else `500 internal-error`. Upstream problem responses pass through unchanged.

## Consequences
- An outage or a slow service is a stable, documented problem type at the edge instead of a Netty error page.
- A `POST` that times out at the edge may still commit upstream: the `504` says "outcome unknown", and the client
  resends with the same `Idempotency-Key` (ADR 0006).
- The gateway health check and metrics need the management port; CI and compose use it.
- Gateway tests run against WireMock upstreams: routing, a slow upstream (`504`), a dead upstream (`503`), internal
  paths, request size, the request id, and retries for `GET` but not `POST`.
