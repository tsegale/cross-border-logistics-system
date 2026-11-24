# 34. Daily ECB reference rates, NAD under the CMA peg, a Bank of Namibia cross-check, and quarantine

Date: 2026-11-24 · Status: Accepted · Supersedes the static table of ADR 0007 · Builds on ADR 0013, ADR 0021, ADR 0031

## Context
Until now every FX quote and cross-currency hold was priced from a table in `application.yml` (EUR/NAD 20). A
forwarder on the Walvis Bay to Hamburg corridor charges in EUR and collects in NAD, so the rate is real money on every
booking. It needs a primary source, a rule for when the source goes quiet, and a way to stop a wrong number from
pricing anything.

## Sources, checked 2026-11-24
- **ECB euro foreign exchange reference rates** ([ECB](https://www.ecb.europa.eu/stats/policy_and_exchange_rates/euro_reference_exchange_rates/html/index.en.html)):
  published around 16:00 CET on TARGET working days, from a concertation between central banks at about 14:10 CET.
  The ECB states they are for information only. ZAR is in the set, NAD is not. Served by the ECB Data Portal API,
  series `EXR.D.ZAR.EUR.SP00.A`, as SDMX-CSV with `format=csvdata`. A live request on 2026-11-24 returned 19.1513 ZAR
  per EUR for 2026-11-23. A series with no observations is a 404 with a JSON body.
- **TARGET closing days** ([ECB press release, 14 December 2000](https://www.ecb.europa.eu/press/pr/date/2000/html/pr001214_4.en.html)):
  Saturdays, Sundays, New Year's Day, Good Friday, Easter Monday, 1 May, Christmas Day and 26 December, the long-term
  calendar the ECB set from 2002 "until further notice". The recorded responses agree: no rates for 25 and 26 December
  2025, 1 January 2026, 3 and 6 April 2026 (`TargetCalendarTest`).
- **The CMA peg.** Namibia joined the Common Monetary Area in 1992, and the Namibia dollar has been at par with the
  rand since it was introduced in 1993. The IMF's 2026 Article IV selected issues paper on Namibia describes the peg as
  "one-to-one" ([IMF](https://www.imf.org/-/media/files/publications/selected-issues-papers/2026/english/sipea2026060.pdf)).
  So EUR/NAD equals EUR/ZAR, and NAD/ZAR is 1.
- **Bank of Namibia.** The bank's website shows a daily table: N$ against USD, GBP, EUR and AOA, today only. Its page
  loads the table from `services.bon.com.na/ratestable/api/rates`, a JSON document that is not documented as an
  API. Three requests in a row on 2026-11-24 answered, each in under a second, with 19.1815 NAD per EUR for
  23 November 2026. That is 0.16% from the ECB's ZAR rate for the same day: the peg holds, and the two fixings are
  taken at different times. The table has no ZAR rate.

## Decision
**The ECB directly, not an aggregator.** Frankfurter, for instance, answered with the same 19.1513 for the same day,
but it republishes the ECB with no service level and is one more party that can be wrong or gone. The ECB is the
primary source, documented, free and stable since 1999. One request per working day is all the load we put on it.

**NAD is derived, never fetched.** EUR to NAD is the ECB's EUR to ZAR rate. NAD/ZAR is 1 under the peg, with source
`cma-peg` and no row behind it, so it is never stale and never refused. Each quote and hold records
`rate_source` (`ecb`, `ecb+cma-peg` or `cma-peg`), `rate_value_date` and `reference_rate_id`, so the rate that priced
any amount can be shown later, by id.

**The Bank of Namibia checks the peg; it never prices.** Its table is undocumented and can change shape without
notice, so a failure, a missing field or an unreadable date means there is no cross-check that time: logged, and the
ECB rate is judged on its other checks. When the table is for the same day as an ECB rate, the implied NAD/ZAR
(Bank of Namibia NAD per EUR over ECB ZAR per EUR) must be within `FX_PEG_TOLERANCE`, 2% by default. That is wide
enough for two fixings hours apart and narrow enough to catch a broken feed or a de-pegging.

**Stored once, never overwritten.** `reference_rates` holds one row per source, pair and value date, with the rate
before it, the cross-check and, if it failed, every check it failed. A trigger refuses an update to any of those, and
any delete. The only change allowed is the one decision a quarantined row gets.

**When a rate is due, and when it is stale.** The newest rate due is today's from 17:00 Frankfurt time on a working
day (16:00 publication plus `FX_PUBLICATION_GRACE`, one hour), otherwise the last working day's. A rate is stale when
it is more than `FX_MAX_AGE_WORKING_DAYS` (2) TARGET working days behind the rate due. A weekend or a closing day
never ages a rate: Thursday's rate is still fresh on Easter Tuesday morning. A stale rate is not used. An FX quote or
a cross-currency hold is refused with `503 fx-rates-stale`, carrying the pair, the rate's day, the day due and the
limit, and `Retry-After: 900`. It is a 503 because nothing about the request is wrong. Logistics' payment relay
retries it like any other 503, so a booking waits for a fresh rate instead of being priced on an old one.

**Quarantine, four eyes to release.** A new rate is `QUARANTINED`, stored but never used, when:
- its value date is not a TARGET working day, or is after the newest one due;
- it moved more than `FX_MAX_DAILY_MOVE` (5%) from the confirmed rate before it. The largest day-on-day move in the
  recorded data is 2.18%, on 8 April 2026;
- the Bank of Namibia's rate for the same day implies a NAD/ZAR off the peg beyond the tolerance;
- it is the first rate ever and the Bank of Namibia has no rate for its day, so there is nothing to judge it by. Only
  the newest observation of a first fetch is taken.

An operator asks for a `RATE_CONFIRMATION` approval (`/api/v1/approvals`, ADR 0021) with a reason. A different
operator approves it, which confirms the rate, or rejects it, which marks it `REJECTED` for good. Nothing is posted
either way: `approval.decided` (schemaVersion 2) carries the rate's id instead of an entry. An expired request leaves
the rate quarantined. Operators and auditors see the rows at `GET /api/v1/reference-rates`.

**Refresh: due-driven, every instance, serialised by the database.** Every instance checks every 15 minutes whether a
newer rate is due, with one indexed query. Only then does it call the ECB for the last 10 observations, so a few
missed days fill in, and the Bank of Namibia only if the ECB had something. Storing takes a transaction-scoped
advisory lock and re-reads the newest stored day under it, so two instances that fetched at once store each day once.
No scheduler lock is needed: a duplicate fetch costs the ECB one request.

**Resilience (ADR 0013).** Connect 2 s, read 5 s. The ECB gets two retries with exponential backoff (500 ms, then
1 s) within a 20 s budget, for timeouts, 5xx and 429. A response that is not the documented CSV is never retried:
the same request gets the same answer. Each source has its own breaker: three failures in a window of five open it
(the ECB's for 5 minutes, the Bank of Namibia's for 30), so a refresh during an outage fails at once.

**Observability (ADR 0031).** Gauges are read from the table, so every instance reports the same:
- `freightflow_fx_reference_rate_age{pair}`: working days behind the rate due, `+Inf` with no rate;
- `freightflow_fx_reference_rate_max_age`;
- `freightflow_fx_reference_rates_quarantined`.

Counters, incremented after commit: `freightflow_fx_reference_rates_stored{status}` and
`freightflow_fx_reference_rates_refreshes{outcome}`, plus the breakers' own metrics. `FxReferenceRateStale` and
`FxReferenceRateQuarantined` page with runbooks (`docs/runbooks/`).

**No test or CI job calls a live API.** Tests use responses recorded from both sources on 2026-11-24
(`banking-service/src/test/resources/ecb`, `bon`): the latest five days, Christmas 2025, Easter 2026, the 404 and the
Bank of Namibia's table. The compose stack in CI points banking at `partner-simulator`. It answers the way the ECB and
the bank do, on today's working days, with the recorded rates, and it has a control endpoint the fire drill uses to
make a source fail, lag or leave the peg. A local `docker compose up` uses the real sources: on 2026-11-24 the first
start confirmed the ECB's 23 November rate, backed by the Bank of Namibia, and the smoke test priced from it.

## Consequences
- FX now depends on an outside source. Two working days without the ECB stop EUR quotes and cross-currency holds, by
  design. NAD/ZAR and same-currency business carry on.
- The Bank of Namibia endpoint may change or vanish. The check then stops working without an error. Its breaker
  metrics, and stored rows without `cross_check_source`, show it; the first rate after a fresh install would then
  need two operators.
- A genuine move over 5% (a rating downgrade, a crisis) waits for two operators before it prices. That is the point:
  until then FX runs on the day before, inside the staleness limit.
- `FX_RATE_PROVIDER=static` keeps the fixed table for tests that need known numbers. Reference rates are never
  deleted, so the tests that store them run on a database of their own (`IsolatedDatabase`).
- The rates are the ECB's information rates plus banking's spread, not dealing rates. A real forwarder would source
  dealing rates from its bank. The provider interface (ADR 0007) is where that plugs in.
