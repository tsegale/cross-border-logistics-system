# 7. Exact decimal money, HALF_EVEN rounding, and locked FX quotes

Date: 2026-11-08 · Status: Accepted

## Context
FreightFlow settles freight invoices and treasury transfers across the Walvis Bay (`NAWVB`, Namibia) to Hamburg (`DEHAM`, Germany) corridor in three currencies: Namibian Dollar (`NAD`), South African Rand (`ZAR`), and Euro (`EUR`). Under the Common Monetary Area (CMA) treaty, `NAD` and `ZAR` are pegged at par (`1 NAD = 1 ZAR`), whereas `EUR` floats against both.

Binary floating-point (`float`/`double`) cannot represent `0.10` exactly and silently leaks sub-cent drift across multi-leg postings and FX conversions. Furthermore, executing a cross-currency transfer at a spot rate looked up mid-transaction exposes the payer to rate movement between quote preview and settlement.

## Decision
- **Representation and rounding**:
  - Every monetary amount in Java is `java.math.BigDecimal`; `float` and `double` are never used for money, rates, weights, or dimensions.
  - Database columns use `NUMERIC(19, 4)` for monetary amounts and `NUMERIC(19, 8)` for exchange rates.
  - Customer-facing amounts are validated and rounded to the ISO 4217 minor-unit scale (`Currency.getInstance(code).getDefaultFractionDigits()`, which is 2 decimal places for `NAD`, `ZAR`, and `EUR`) using `RoundingMode.HALF_EVEN` (banker's rounding) before being normalized to 4-decimal storage scale (`NUMERIC(19, 4)`). Requests with excess minor-unit precision (e.g., `10.005 EUR`) are rejected with `400 Bad Request`.
- **Locked FX quotes (`POST /api/v1/fx-quotes`)**:
  - Cross-currency transfers require a pre-locked `FxQuote` (`id`, `sellCurrency`, `buyCurrency`, `sellAmount`, `buyAmount`, `rate`, `expiresAt`, `consumed`).
  - `ExchangeRateProvider` is an interface with a deterministic configuration-backed implementation (`StaticConfiguredExchangeRateProvider`) in Phase 1 that enforces the 1:1 `NAD`/`ZAR` CMA peg (`rate = 1.00000000`).
  - An `FxQuote` is valid for a configurable window (default 5 minutes, checked via an injected `Clock`) and is marked `consumed = true` atomically inside the transfer transaction (`SELECT ... FOR UPDATE` on `fx_quotes`). Expired quotes fail with `409 fx-quote-expired`; reused quotes fail with `409 fx-quote-already-consumed`.
- **Four-posting FX journal entry**:
  - A cross-currency transfer from a `NAD` customer account to a `EUR` customer account records **four** postings in a single `journal_entries` row through the service's internal `FX_POSITION` accounts (`FX_POSITION-NAD` and `FX_POSITION-EUR`):
    1. `DEBIT` source customer (`NAD`, `sellAmount`)
    2. `CREDIT` `FX_POSITION-NAD` (`NAD`, `sellAmount`)
    3. `DEBIT` `FX_POSITION-EUR` (`EUR`, `buyAmount`)
    4. `CREDIT` destination customer (`EUR`, `buyAmount`)
  - Every currency (`NAD` and `EUR`) balances to zero (`SUM(DEBIT) == SUM(CREDIT)`) inside the same journal entry.

## Consequences
- The per-currency double-entry invariant (`SUM(DEBIT) == SUM(CREDIT)`) holds without exception across both single-currency and cross-currency transfers, verified by the PostgreSQL constraint trigger and `GET /api/v1/ledger/reconciliation`.
- Net FX exposure sits explicitly in the `FX_POSITION-<CCY>` accounts rather than being hidden in unbalanced cross-currency rows.
