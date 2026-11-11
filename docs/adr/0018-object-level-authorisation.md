# 18. Object-level authorisation and the policy table

Date: 2026-11-11 · Status: Accepted

## Context
A valid token says who is calling, not what they may touch. OWASP API Security Top 10 2023 puts broken object-level
authorisation (API1) first: an endpoint that checks the role but not the object lets any customer read or move any
other customer's money by changing an id. Every FreightFlow endpoint that takes an id must check that this caller may
use that object.

## Decision
- **Ownership is data.** Accounts belong to a customer organisation (`accounts.owner_organization_id`, the Keycloak
  organisation id, ADR 0016); the free-text `holder_reference` is gone (banking V7). FX quotes and freight quotes
  record the organisation that asked for them (an operator's have none). A shipment records the booking organisation,
  the payer's organisation and its assigned carrier (logistics V8). Rows from before Phase 3 belong to the
  "unassigned" organisation `00000000-0000-0000-0000-000000000000`, which no token can name.
- **The check is in the service**, on the loaded object, not in the controller or the gateway: `Caller.mayAccess`
  (operators and auditors see everything; a customer, its own organisation's objects), `Shipment.visibleTo` (also
  the assigned carrier). A customer creating something creates it for its own organisation; naming another is a 403.
- **A foreign object is a 404**, the same problem as an id that does not exist, so ids cannot be probed.
- **Money cannot be reserved from someone else's account.** logistics sends the booking organisation with every
  hold; banking refuses (404) a payer account that organisation does not own (ADR 0019).

### Policy table
"Own" is a customer of the organisation that owns the object; "foreign" a customer of another; the carrier is the
one assigned to the shipment. 4xx codes are what the caller gets.

banking-service:

| Endpoint | Own | Foreign | Operator | Auditor | Carrier | logistics-service | Anonymous |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `GET /accounts/{id}`, `GET /accounts/{id}/postings` | 200 | 404 | 200 | 200 | 403 | 403 | 401 |
| `POST /accounts` (for an organisation) | 201 (own) | 403 | 201 | 403 | 403 | 403 | 401 |
| `POST /accounts/{id}/deposits` | 403 | 403 | 201 | 403 | 403 | 403 | 401 |
| `POST /transfers` (from the account) | 201 | 404 | 201 | 403 | 403 | 403 | 401 |
| `GET /transfers/{id}` | 200 | 404 | 200 | 200 | 403 | 403 | 401 |
| `POST /fx-quotes` | 201 | 201 | 201 | 403 | 403 | 403 | 401 |
| `GET /fx-quotes/{id}`, a transfer using it | 200 | 404 | 200 | 200 | 403 | 403 | 401 |
| `GET /fx-rates` | 200 | 200 | 200 | 200 | 200 | 200 | 401 |
| `GET /journal-entries/{id}` | 200 | 404 | 200 | 200 | 403 | 403 | 401 |
| `POST /journal-entries/{id}/reversals` | 403 | 403 | 201 | 403 | 403 | 403 | 401 |
| `GET /ledger/reconciliation` | 403 | 403 | 200 | 200 | 403 | 403 | 401 |
| `/holds/**` (authorise, get, find, capture, extend, release) | 403 | 403 | 403 | 403 | 403 | 2xx | 401 |
| API docs | 200 | 200 | 200 | 200 | 403 | 403 | 401 |

logistics-service:

| Endpoint | Own | Foreign | Operator | Auditor | Carrier | Other carrier | Anonymous |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `POST /quotes` | 201 | 201 | 201 | 403 | 403 | 403 | 401 |
| `GET /quotes/{id}` | 200 | 404 | 200 | 200 | 403 | 403 | 401 |
| `POST /shipments` (on the quote) | 201 | 404 | 201 | 403 | 403 | 403 | 401 |
| `GET /shipments/{id}` | 200 | 404 | 200 | 200 | 200 | 404 | 401 |
| `GET /shipments` (filtered to what the caller may see) | 200 | 200 | 200 | 200 | 200 | 200 | 401 |
| `POST /shipments/{id}/tracking-events` | 403 | 403 | 201 | 403 | 201 | 404 | 401 |
| `POST /shipments/{id}/cancellation` (booking organisation only) | 200 | 404 | 200 | 403 | 403 | 403 | 401 |
| `PUT /shipments/{id}/carrier` | 403 | 403 | 200 | 403 | 403 | 403 | 401 |
| `GET /payment-commands`, `GET /payment-commands/{id}` | 403 | 403 | 200 | 200 | 403 | 403 | 401 |
| `POST /payment-commands/{id}/retry` | 403 | 403 | 200 | 403 | 403 | 403 | 401 |
| `GET /shipments/track/{trackingNumber}` (no customer data) | 200 | 200 | 200 | 200 | 200 | 200 | 200 |
| API docs | 200 | 200 | 200 | 200 | 403 | 403 | 401 |

- **Tested exhaustively.** `AuthorisationMatrixTest` in each service holds this table as data and runs every row for
  every column against the real security chain and a real database: 140 cases in banking, 91 in logistics. A
  combination the table does not allow fails the test, and so does a refusal it does allow. Separate tests check that
  lists are filtered, and that a payer organisation which did not book cannot cancel.

## Consequences
- Adding an endpoint means adding a row, or the matrix has no case for it; review catches a missing row.
- Some counterparty facts stay visible on purpose: a transfer's target may be any customer account (paying another
  company is the point), so a customer learns that an account number exists by paying into it.
- Accounts and shipments from before Phase 3 are invisible to customers until an operator assigns them.
