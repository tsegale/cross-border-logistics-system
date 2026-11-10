# RFC 9457 Problem Details Registry

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
| `https://freightflow.dev/problems/internal-error` | `500 Internal Server Error` | Internal error | — | Anything unexpected. The detail is generic; the cause is only in the service log |

---

## `banking-service` Problem Types

| `type` URI | HTTP Status | Title | Extension Fields | When Returned |
| --- | --- | --- | --- | --- |
| `https://freightflow.dev/problems/account-not-found` | `404 Not Found` | Account not found | `identifier` | Account UUID or account number does not exist |
| `https://freightflow.dev/problems/account-not-active` | `409 Conflict` | Account is not active | `accountNumber`, `status` | Attempted posting or status change on a `FROZEN` or `CLOSED` account |
| `https://freightflow.dev/problems/insufficient-funds` | `409 Conflict` | Insufficient funds | `accountNumber` | Debit would cause a `CUSTOMER` account balance to go negative |
| `https://freightflow.dev/problems/unbalanced-journal-entry` | `422 Unprocessable Content` | Journal entry is not balanced | `currency`, `debits`, `credits` | Per-currency sum of `DEBIT` postings does not equal sum of `CREDIT` postings |
| `https://freightflow.dev/problems/currency-mismatch` | `422 Unprocessable Content` | Currency mismatch | — | Posting currency does not match account currency, or cross-currency transfer lacks a matching FX quote |
| `https://freightflow.dev/problems/same-account-transfer` | `422 Unprocessable Content` | Source and destination accounts must differ | — | Transfer source and destination resolve to the same account |
| `https://freightflow.dev/problems/journal-entry-not-found` | `404 Not Found` | Journal entry not found | `entryId` | Journal entry UUID does not exist |
| `https://freightflow.dev/problems/journal-entry-already-reversed` | `409 Conflict` | Journal entry has already been reversed | `entryId` | Attempted to reverse an entry a second time (`uq_journal_entries_reverses_once`) |
| `https://freightflow.dev/problems/transfer-not-found` | `404 Not Found` | Transfer not found | `transferId` | Transfer UUID does not exist |
| `https://freightflow.dev/problems/fx-quote-not-found` | `404 Not Found` | FX quote not found | `quoteId` | FX quote UUID does not exist |
| `https://freightflow.dev/problems/fx-quote-expired` | `409 Conflict` | FX quote has expired | `quoteId`, `expiresAt` | FX quote `expiresAt` is not after `clock.instant()` |
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
| `https://freightflow.dev/problems/precondition-failed` | `412 Precondition Failed` | Precondition failed | `expectedETag`, `currentETag` | `If-Match` header does not match current `shipments.version` ETag |
