# 6. Transactional idempotency keys with advisory locks

Date: 2026-11-08 · Status: Accepted

## Context
Clients and upstream gateways retry `POST` requests (`POST /api/v1/accounts/{id}/deposits`, `POST /api/v1/transfers`, `POST /api/v1/journal-entries/{id}/reversals`, `POST /api/v1/shipments`) when a network timeout or connection reset hides whether the server committed. Without server-side idempotency, a retry either double-debits a customer's account or books two shipments against the same quote.

We need an idempotency mechanism that follows `draft-ietf-httpapi-idempotency-key-header-08`, works across multiple service instances without requiring Redis in Phase 1, and commits atomically with the domain write.

## Decision
- Every money-moving `POST` in `banking-service` and shipment-booking `POST` in `logistics-service` requires an `Idempotency-Key` header (1–128 ASCII characters; missing or blank returns `400 idempotency-key-missing`).
- Each service stores an `idempotency_keys(scope, idempotency_key, request_hash, status, http_status, response_body, created_at, expires_at)` table in its own PostgreSQL database.
- On entry to a @Transactional idempotent command:
  1. Compute a canonical SHA-256 `request_hash` over `scope + ":" + canonical_json(payload)` (with properties sorted alphabetically).
  2. Acquire a transaction-scoped PostgreSQL advisory lock (`SELECT pg_advisory_xact_lock(hashtext(:scope), hashtext(:key))`) with a short lock timeout (`idempotency.lock-timeout`, default 5 s). If another transaction is concurrently executing the same `(scope, key)` and exceeds the lock timeout, return `409 idempotency-key-in-flight` with `Retry-After: 1`.
     The timeout is set with `set_config('lock_timeout', ..., true)` and the previous value is restored right after the lock is granted (`LockTimeouts.withLockTimeout`). `SET LOCAL` alone lasts until commit, so in Phase 1 the 5 s cap also applied to the account row locks taken later in the same transaction, which then failed with an unmapped `55P03` (a 500). Account locks now have their own timeout (`ledger.account-lock-timeout`, default 10 s), and `55P03`, `40P01` and `40001` map to retryable `lock-timeout` (503) and `transaction-conflict` (409) problems with `Retry-After`.
  3. Look up `(scope, idempotency_key)` in `idempotency_keys`:
     - If an unexpired record exists with a **different** `request_hash`, return `422 idempotency-key-payload-mismatch`.
     - If an unexpired `COMPLETED` record exists with the **same** `request_hash`, replay the stored `http_status` and `response_body` with header `Idempotent-Replayed: true`.
  4. Otherwise execute the domain command and insert the `COMPLETED` idempotency record in the **same PostgreSQL transaction** (`expires_at = now + 24h`).
- A scheduled cleanup job (`DELETE FROM idempotency_keys WHERE expires_at <= now()`) purges expired keys every 30 minutes; because `DELETE ... WHERE expires_at <= ?` is idempotent, multiple replicas can run the job safely without coordination.

## Consequences
- **Atomic commit**: The ledger entry (or shipment) and its idempotency record commit or roll back together in one local ACID transaction — no window where a crash leaves a committed transfer without its stored response.
- **Concurrent duplicate safety**: Verified by `TransferConcurrencyIntegrationTest` firing 20 simultaneous requests with the same `Idempotency-Key` — all 20 receive the identical `201 Created` transfer payload and exactly 1 journal entry is written.
- **Database storage overhead**: Storing serialized JSON responses for 24 hours adds one row per mutating `POST`, indexed on `expires_at` for fast range deletes.
