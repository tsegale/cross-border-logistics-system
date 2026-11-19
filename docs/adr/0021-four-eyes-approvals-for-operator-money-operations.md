# 21. Four-eyes approvals for operator money operations

Date: 2026-11-19 · Status: Accepted · Revises ADR 0018's policy table

## Context
After Phase 3 one operator, alone, could move any customer's money: `POST /transfers` out of any account, create money
from CLEARING with `POST /accounts/{id}/deposits`, and reverse any entry with `POST /journal-entries/{id}/reversals`.
Each of those posts at once, on one person's word. An operator with bad intent, a phished operator session or a
mistyped amount goes straight into the ledger.

German banking supervision expects incompatible duties to be separated and critical actions to be checked by a second
person: the four-eyes principle (Vier-Augen-Prinzip), part of the organisational requirements of MaRisk AT 4.3.1. A
freight forwarder's internal ledger is not a bank, but the customers whose money it holds expect the same discipline,
and an auditor reviewing it will look for it first.

## Options
1. **Keep single-operator posting, add after-the-fact review.** Cheap, but a review finds the damage after the money
   has moved, and a reversal of a bad entry would itself be single-operator.
2. **Maker-checker in the client** (the back-office UI asks for a second login). Nothing stops a call straight to the
   API, so the control is only as strong as the UI.
3. **Maker-checker in banking itself**: an operator *requests*, a different operator *approves*, and only the approval
   posts. The database refuses a deposit or reversal entry that has no approval.

## Decision
Option 3, as a resource of its own: `/api/v1/approvals`.

- `POST /api/v1/approvals` (operator, `Idempotency-Key`): a `DEPOSIT` (account, amount, currency, reference) or a
  `REVERSAL` (journal entry, reason). It is checked as fully as the posting would be (account type and currency,
  minor units, entry exists and is not reversed) so the checker never approves something that cannot post, and is
  stored `PENDING` with `expires_at = now + APPROVAL_TTL` (24 h by default). One open reversal per entry.
- `POST /api/v1/approvals/{id}/approval` (operator, `Idempotency-Key`, it moves money): locks the request row, checks it
  is still `PENDING`, that the caller is not the requester (403 `four-eyes-required`) and that it has not expired
  (409 `approval-expired`), then posts the entry in the same transaction and marks the request `APPROVED`.
  `POST /api/v1/approvals/{id}/rejection` (operator, a comment required) marks it `REJECTED` and posts nothing. A
  requester can neither approve nor reject their own request; an unwanted request is rejected by a colleague or left
  to expire.
- `GET /api/v1/approvals?status=` and `GET /api/v1/approvals/{id}`: operators and auditors, oldest first.
- A sweeper marks requests past their expiry `EXPIRED` every minute, each under its row lock, on every instance.
- The old `POST /accounts/{id}/deposits` and `POST /journal-entries/{id}/reversals` are gone (403 for everyone).
  Operators no longer `POST /transfers` at all: only the owning customer moves its money. A wrong customer transfer is
  corrected with a four-eyes reversal.

**Both people are recorded.** The request stores the maker (subject, client, request id) and the checker; the posted
entry's actor (ADR 0020) is the checker and its `approval_id` points at the request, so an auditor sees both from the
entry. Staff see `approvalId` on a journal entry; customers do not.

**Enforced by the database, not only the service** (V8):
- `chk_approval_four_eyes`: `decided_by_subject <> requested_by_subject`.
- `chk_approval_posted`: an entry id exactly when `APPROVED`; `journal_entries.approval_id` is `UNIQUE`, so one
  approval can never post two entries, whatever races.
- `trg_journal_entries_require_approval`: a new `DEPOSIT` or `REVERSAL` entry without an approval is refused. No code
  path, present or future, can post one on one person's word.

**Concurrency.** Two checkers approving at once serialise on the request's row lock (`SELECT ... FOR UPDATE`, under the
ledger's lock timeout): the second finds it `APPROVED` and gets 409 `approval-not-pending`. The lock order is the
request row, then the account rows in ascending id (ADR 0005); nothing locks them the other way round.

### Low-value deposits do not skip it
A configurable threshold below which one operator may deposit alone was considered and rejected:
- **It is bypassed by splitting** (structuring): a 100,000 EUR deposit becomes 200 deposits of 500 EUR under a 1,000 EUR
  threshold. Closing that needs a cumulative cap per operator per period, a counter row that every deposit of that
  operator updates, which serialises them and adds the contention ADR 0010 removed from CLEARING.
- **The volume does not call for it.** A deposit here is an operator matching an incoming bank transfer to a customer
  account: tens a day for a B2B freight forwarder, not thousands. A second operator costs minutes, not headcount.
- **A deposit creates money.** It is the one operation with no counterparty in the ledger to notice a mistake.

If volume ever needs it, the place is an automated bank-statement import (a machine client with its own scope) that
matches statements to accounts, with exceptions going to the approval queue; not a human threshold.

## Consequences
- Every deposit and reversal is two requests by two people. Scripts, the smoke test, the benchmarks and the tests fund
  accounts through the approval flow, as `operator` and `second-operator` (a new persona in the realm, ADR 0016).
- ADR 0010's deposit benchmark now times the approval, the request that posts against CLEARING; the request before it
  is set-up. Numbers from before Phase 4 timed the one-step deposit and are kept, labelled, for comparison.
- `approval.decided` becomes an event later in Phase 4 (`docs/events.md`), so other services can react to decisions.
- Still open (threat model, insider misuse): two colluding operators, or one person holding two operator logins. The
  realm gives each person one account; separation of the people is an organisational control. An alert on one pair
  of operators approving each other far more than others is part of Phase 5's dashboards.
- Tested: `ApprovalIntegrationTest` (maker cannot decide, replayed and second approvals, idempotency, validation,
  rejection, expiry, two concurrent checkers produce one entry in five rounds, the database refuses an unapproved
  deposit and a maker-checker), and the revised policy table in `AuthorisationMatrixTest`.
