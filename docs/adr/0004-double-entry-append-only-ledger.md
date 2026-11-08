# 4. Double-entry, append-only ledger with database-enforced invariants

Date: 2026-11-08 · Status: Accepted

## Context
`banking-service` settles corridor freight invoices and treasury transfers across `NAD`, `ZAR`, and `EUR`. If an account balance is stored only as a mutable column updated in place without a double-entry journal, a bug or partial failure can create or destroy money without leaving an audit trail. Conversely, if balances are derived only by summing all historical postings on every read, balance checks under high transfer concurrency degrade as the posting table grows.

Furthermore, application-level checks alone do not protect the ledger against ad-hoc SQL or future code paths that accidentally `UPDATE` or `DELETE` historical entries or commit an unbalanced entry.

## Decision
1. **Account numbers with ISO 7064 MOD 97-10 check digits**:
   - Customer and system accounts receive a 16-character account number `FF<2 check digits><3-letter currency><9 digits>` (e.g., `FF87NAD000000001`), using the IBAN ISO/IEC 7064 MOD 97-10 polynomial check so 100% of single-character substitutions and adjacent transpositions are rejected with `400 invalid-account-number` before querying the database.
   - Primary keys on `accounts`, `journal_entries`, `postings`, `transfers`, and `fx_quotes` are time-ordered **UUIDv7** values for sequential B-tree index locality.
2. **Append-only `journal_entries` and `postings` enforced by PostgreSQL triggers**:
   - `BEFORE UPDATE OR DELETE` triggers (`trg_journal_entries_append_only` and `trg_postings_append_only`) raise SQLSTATE `55000` on any attempt to mutate or delete a committed journal entry or posting.
   - Corrections happen only through `POST /api/v1/journal-entries/{id}/reversals`, which writes a new `REVERSAL` journal entry with negated postings and sets `reverses_entry_id` (constrained by `UNIQUE (reverses_entry_id)` so an entry can be reversed at most once).
3. **Database-enforced per-currency zero-sum invariant**:
   - A `DEFERRABLE INITIALLY DEFERRED` constraint trigger (`trg_verify_journal_entry_balanced`) runs at transaction commit and verifies that every `journal_entry_id` has at least two postings and that `SUM(amount) = 0.0000` for every currency in that entry.
   - Signed `postings.amount` (`+` credit, `-` debit) simplifies both the commit trigger (`SUM(amount) = 0`) and the reconciliation query (`GET /api/v1/ledger/reconciliation`).
4. **Cached `accounts.balance` + `postings.running_balance` + keyset pagination**:
   - `accounts.balance` caches the current balance (protected by `CHECK (account_type <> 'CUSTOMER' OR balance >= 0)`), while each posting records `running_balance` immediately after that posting is applied.
   - `GET /api/v1/accounts/{id}/postings` uses keyset pagination over `(account_id, created_at DESC, id DESC)` backed by `idx_postings_account_statement`.

### Index Verification (`EXPLAIN (ANALYZE, BUFFERS)` on `90,688` `postings` rows)

```sql
EXPLAIN (ANALYZE, BUFFERS)
SELECT p.id, p.journal_entry_id, p.amount, p.currency, p.running_balance, p.created_at
FROM postings p
WHERE p.account_id = '01930000-0000-7000-8000-000000000001'::uuid
  AND (p.created_at, p.id) < (TIMESTAMPTZ '2026-01-01 03:30:00Z', '01960000-3138-7000-8000-000000003138'::uuid)
ORDER BY p.created_at DESC, p.id DESC
LIMIT 21;
```

```text
 Limit  (cost=0.42..13.59 rows=21 width=55) (actual time=0.023..0.028 rows=21 loops=1)
   Buffers: shared hit=4
   ->  Index Scan using idx_postings_account_statement on postings p  (cost=0.42..4450.52 rows=7094 width=55) (actual time=0.022..0.026 rows=21 loops=1)
         Index Cond: ((account_id = '01930000-0000-7000-8000-000000000001'::uuid) AND (ROW(created_at, id) < ROW('2026-01-01 03:30:00+00'::timestamp with time zone, '01960000-3138-7000-8000-000000003138'::uuid)))
         Buffers: shared hit=4
 Planning:
   Buffers: shared hit=28
 Planning Time: 0.805 ms
 Execution Time: 0.110 ms
```

## Consequences
- Every balance mutation is backed by an immutable, balanced journal entry, and `GET /api/v1/ledger/reconciliation` can verify in a single query that `SUM(postings) == 0` per currency and `accounts.balance == SUM(postings)` for every account.
- Keyset pagination reads `4` buffer pages in `0.110 ms` at ~90k postings with zero `OFFSET` scan degradation.
