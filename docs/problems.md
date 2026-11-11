# RFC 9457 Problem Details Registry

Extension members never reuse the standard member names (`type`, `title`, `status`, `detail`, `instance`):
`status` is always the HTTP status code. Phase 1's `account-not-active` used `status` for the account status; since
Phase 2 that member is `accountStatus`.

Every error emitted by `banking-service` and `logistics-service` uses `Content-Type: application/problem+json` ([RFC 9457](https://www.rfc-editor.org/rfc/rfc9457)) with a stable `type` URI under `https://freightflow.dev/problems/<slug>`.

## Common Problem Types (`banking-service` & `logistics-service`)

| `type` URI | HTTP Status | Title | Extension Fields | When Returned |
| --- | --- | --- | --- | --- |
| `https://freightflow.dev/problems/validation-failed` | `400 Bad Request` | Request validation failed | `errors: [{field, message}]` | Bean Validation constraint failure on request DTO fields |
| `https://freightflow.dev/problems/invalid-request-body` | `400 Bad Request` | Invalid request body | — | Malformed JSON body or unrecognized enum value |
| `https://freightflow.dev/problems/invalid-argument` | `400 Bad Request` | Invalid request argument | — | Domain argument violation (e.g., excess minor-unit precision) |
| `https://freightflow.dev/problems/unsupported-currency` | `400 Bad Request` | Unsupported currency | `currency`, `supportedCurrencies` | Currency is not in `{NAD, ZAR, EUR}` |
| `https://freightflow.dev/problems/invalid-account-number` | `400 Bad Request` | Invalid account number | `accountNumber` | Account number fails `FF<2 check digits><CCY><9 digits>` format or ISO 7064 MOD 97-10 check digits |
| `https://freightflow.dev/problems/invalid-cursor` | `400 Bad Request` | Invalid pagination cursor | — | Malformed Base64URL keyset cursor, mismatched scope/filter, or invalid `size` parameter |
| `https://freightflow.dev/problems/idempotency-key-missing` | `400 Bad Request` | Missing or invalid Idempotency-Key header | — | `Idempotency-Key` header is missing, blank, or exceeds 128 characters |
| `https://freightflow.dev/problems/idempotency-key-payload-mismatch` | `422 Unprocessable Content` | Idempotency-Key reused with different request payload | — | Same `(scope, Idempotency-Key)` replayed with a different canonical SHA-256 request hash |
| `https://freightflow.dev/problems/idempotency-key-in-flight` | `409 Conflict` (`Retry-After: 1`) | Request with this Idempotency-Key is currently in flight | — | Concurrent request holds the transactional advisory lock or `IN_PROGRESS` record for the same key |
| `https://freightflow.dev/problems/database-unavailable` | `503 Service Unavailable` (`Retry-After: 5`) | Temporarily unavailable | — | PostgreSQL connection pool cannot reach the service database |
| `https://freightflow.dev/problems/lock-timeout` | `503 Service Unavailable` (`Retry-After: 1`) | Resource busy | — | SQLSTATE `55P03`: a row lock was not granted within its lock timeout. The transaction rolled back; retry the same request with the same `Idempotency-Key` |
| `https://freightflow.dev/problems/transaction-conflict` | `409 Conflict` (`Retry-After: 1`) | Transaction conflict | — | SQLSTATE `40P01` (deadlock) or `40001` (serialization failure). The transaction rolled back; retry the same request |
| `https://freightflow.dev/problems/data-conflict` | `409 Conflict` | Conflicting data | — | A database constraint rejected the write (for example two requests racing on a unique key). The constraint name is only in the log |
| `https://freightflow.dev/problems/internal-error` | `500 Internal Server Error` | Internal error | — | Anything unexpected. The detail is generic; the cause is only in the service log |

---

## `banking-service` Problem Types

| `type` URI | HTTP Status | Title | Extension Fields | When Returned |
| --- | --- | --- | --- | --- |
| `https://freightflow.dev/problems/account-not-found` | `404 Not Found` | Account not found | `identifier` | Account UUID or account number does not exist |
| `https://freightflow.dev/problems/account-not-active` | `409 Conflict` | Account is not active | `accountNumber`, `accountStatus` | Attempted posting or status change on a `FROZEN` or `CLOSED` account |
| `https://freightflow.dev/problems/insufficient-funds` | `409 Conflict` | Insufficient funds | `accountNumber`, `currency`, `availableBalance`, `requestedAmount` | A debit or a new hold exceeds the `CUSTOMER` account's available balance (ledger balance minus active holds) |
| `https://freightflow.dev/problems/unbalanced-journal-entry` | `422 Unprocessable Content` | Journal entry is not balanced | `currency`, `debits`, `credits` | Per-currency sum of `DEBIT` postings does not equal sum of `CREDIT` postings |
| `https://freightflow.dev/problems/currency-mismatch` | `422 Unprocessable Content` | Currency mismatch | — | Posting currency does not match account currency, or cross-currency transfer lacks a matching FX quote |
| `https://freightflow.dev/problems/same-account-transfer` | `422 Unprocessable Content` | Source and destination accounts must differ | — | Transfer source and destination resolve to the same account |
| `https://freightflow.dev/problems/journal-entry-not-found` | `404 Not Found` | Journal entry not found | `entryId` | Journal entry UUID does not exist |
| `https://freightflow.dev/problems/journal-entry-already-reversed` | `409 Conflict` | Journal entry has already been reversed | `entryId` | Attempted to reverse an entry a second time (`uq_journal_entries_reverses_once`) |
| `https://freightflow.dev/problems/transfer-not-found` | `404 Not Found` | Transfer not found | `transferId` | Transfer UUID does not exist |
| `https://freightflow.dev/problems/fx-quote-not-found` | `404 Not Found` | FX quote not found | `quoteId` | FX quote UUID does not exist |
| `https://freightflow.dev/problems/fx-quote-expired` | `409 Conflict` | FX quote has expired | `quoteId`, `expiresAt` | FX quote `expiresAt` is not after `clock.instant()` |
| `https://freightflow.dev/problems/hold-not-found` | `404 Not Found` | Hold not found | `holdId` | Hold UUID does not exist (internal API) |
| `https://freightflow.dev/problems/hold-not-active` | `409 Conflict` | Hold is not active | `holdId`, `holdStatus` | Capture, release or extension of a hold that is already `CAPTURED`, `RELEASED` or `EXPIRED` |
| `https://freightflow.dev/problems/hold-expired` | `409 Conflict` | Hold has expired | `holdId`, `expiresAt` | Capture or extension after `expiresAt`; the sweeper releases the hold |
| `https://freightflow.dev/problems/capture-exceeds-hold` | `422 Unprocessable Content` | Capture exceeds the hold | `holdId`, `holdAmount`, `requestedAmount` | Capture amount above the authorised amount |
| `https://freightflow.dev/problems/invalid-hold` | `422 Unprocessable Content` | Invalid hold | — | Payer is not a customer account; `expiresAt` is not in the future or more than `max-ttl` (30 days) away; an extension's `expiresAt` is not later than the current one or beyond `max-lifetime` (120 days from the hold's creation) |
| `https://freightflow.dev/problems/fx-quote-already-consumed` | `409 Conflict` | FX quote has already been consumed | `quoteId` | FX quote was already used by a prior cross-currency transfer |

---

## `logistics-service` Problem Types

| `type` URI | HTTP Status | Title | Extension Fields | When Returned |
| --- | --- | --- | --- | --- |
| `https://freightflow.dev/problems/unknown-location` | `422 Unprocessable Content` | Unknown UN/LOCODE location | `field`, `unlocode` | Location code does not exist in seeded corridor `locations` |
| `https://freightflow.dev/problems/same-origin-destination` | `422 Unprocessable Content` | Invalid route | `origin`, `destination`, `mode` | Quote `origin` and `destination` are the same UN/LOCODE |
| `https://freightflow.dev/problems/unsupported-route` | `422 Unprocessable Content` | Unsupported corridor lane or mode | `origin`, `destination`, `mode` | No tariff exists for `(origin, destination, mode)` (e.g., `SEA_LCL` from inland `NAWDH`) |
| `https://freightflow.dev/problems/nothing-to-quote` | `422 Unprocessable Content` | Nothing to quote for this payer | `incoterm`, `payerRole` | The quoting party pays for no leg under the Incoterm (`EXW` asked by the shipper, `DDP` asked by the consignee) |
| `https://freightflow.dev/problems/quote-not-found` | `404 Not Found` | Freight quote not found | `quoteId` | Freight quote UUID does not exist |
| `https://freightflow.dev/problems/quote-expired` | `409 Conflict` | Freight quote has expired | `quoteId`, `validUntil` | Freight quote `validUntil` has passed when booking a shipment |
| `https://freightflow.dev/problems/shipment-not-found` | `404 Not Found` | Shipment not found | `identifier` | Shipment UUID or valid tracking number does not exist |
| `https://freightflow.dev/problems/invalid-tracking-number` | `400 Bad Request` | Invalid tracking number | `trackingNumber` | Tracking number fails `FF-<8 Crockford Base32 chars>-<check>` format or ISO 7064 MOD 37-2 check character |
| `https://freightflow.dev/problems/invalid-shipment-state-transition` | `409 Conflict` | Invalid shipment state transition | `currentStatus`, `requestedStatus`, `allowedNextStatuses` | Requested state transition is not in `currentStatus.allowedNextStatuses()` |
| `https://freightflow.dev/problems/payment-command-not-found` | `404 Not Found` | Payment command not found | `commandId` | Internal operator API: no such payment command |
| `https://freightflow.dev/problems/payment-command-not-parked` | `409 Conflict` | Payment command is not parked | `commandId`, `commandStatus` | Operator retry of a command that is not `PARKED` |
| `https://freightflow.dev/problems/precondition-failed` | `412 Precondition Failed` | Precondition failed | `expectedETag`, `currentETag` | `If-Match` header does not match current `shipments.version` ETag |

---

## `api-gateway` Problem Types (ADR 0015)

Errors the gateway produces itself. Problems returned by a service pass through unchanged. Every gateway problem
carries the request's `requestId` (also in the `X-Request-Id` header).

| `type` URI | HTTP Status | Title | When Returned |
| --- | --- | --- | --- |
| `https://freightflow.dev/problems/unauthenticated` | `401 Unauthorized` (`WWW-Authenticate: Bearer`, or `Bearer error="invalid_token"`) | Authentication required | No bearer token, or one that is not signed by the realm's key with RS256, not for this issuer and audience, or expired (ADR 0017) |
| `https://freightflow.dev/problems/forbidden` | `403 Forbidden` | Forbidden | The token is valid but its role may not use this route (ADR 0017). A resource the caller may not see is a 404 from the service instead (ADR 0018) |
| `https://freightflow.dev/problems/issuer-unavailable` | `503 Service Unavailable` (`Retry-After: 5`) | Token cannot be verified right now | The token's signing key is not cached and Keycloak's keys cannot be fetched: retry with the same token |
| `https://freightflow.dev/problems/rate-limited` | `429 Too Many Requests` (`Retry-After`, `RateLimit-Policy`, `RateLimit`) | Too many requests | The caller's token bucket for a money-moving route is empty; retry with the same `Idempotency-Key` |
| `https://freightflow.dev/problems/rate-limiter-unavailable` | `503 Service Unavailable` (`Retry-After: 1`) | Temporarily unavailable | The rate limiter's Redis is unreachable; money-moving routes fail closed |
| `https://freightflow.dev/problems/not-found` | `404 Not Found` | Not found | No route matches (the gateway's own actuator is only on the management port 8085), or the path is internal (`/api/v1/holds/**`) |
| `https://freightflow.dev/problems/method-not-allowed` | `405 Method Not Allowed` | Method not allowed | The route does not accept the method |
| `https://freightflow.dev/problems/request-too-large` | `413 Content Too Large` | Request too large | Body over 1 MB, by `Content-Length` or while reading a chunked body |
| `https://freightflow.dev/problems/invalid-request` | `4xx` | (reason phrase) | Any other client error the gateway raises itself |
| `https://freightflow.dev/problems/upstream-error` | `502 Bad Gateway` | Bad gateway | The service answered with an invalid response |
| `https://freightflow.dev/problems/upstream-unavailable` | `503 Service Unavailable` (`Retry-After: 5`) | Service unavailable | The service cannot be reached (connection refused, unknown host, connect timeout) |
| `https://freightflow.dev/problems/upstream-timeout` | `504 Gateway Timeout` | Upstream timeout | No answer within the route's 15 s response timeout. A request that changes data may still have been applied: resend it with the same `Idempotency-Key` |
| `https://freightflow.dev/problems/internal-error` | `500 Internal Server Error` | Internal error | Anything unexpected in the gateway |
