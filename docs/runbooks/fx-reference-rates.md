# Reference rates stale or quarantined

Alerts: FxReferenceRateStale, FxReferenceRateQuarantined

**What it means.** banking prices EUR against NAD and ZAR from the ECB's daily reference rate (ADR 0034).
- **FxReferenceRateStale (page).** The newest confirmed EUR/ZAR rate is more than `FX_MAX_AGE_WORKING_DAYS` TARGET
  working days behind the rate due, or there is none. EUR quotes and cross-currency holds are refused with
  `503 fx-rates-stale`. Logistics issues quotes without an indicative total, and its payment relay retries those holds
  until they park. NAD/ZAR (the peg) and same-currency business carry on.
- **FxReferenceRateQuarantined (ticket).** A fetched rate failed a check and prices nothing until two operators decide
  it. The checks: a move of more than 5% from the confirmed rate before it, a Bank of Namibia rate implying NAD/ZAR off
  the peg by more than 2%, a value date that is a closing day or in the future, or a first rate with no second source.
  Both alerts fire together when the only rate is quarantined.

**Check.**
- [Money](http://127.0.0.1:3000/d/money), "Reference rates": the age against its limit, quarantined rates, refreshes
  by outcome and the source breakers.
- The rows, as an operator or auditor: `GET /api/v1/reference-rates?status=QUARANTINED`. Each has a
  `quarantineReason` that lists every failed check, and the rates it was compared with (`previousRate`,
  `crossCheckRate`).
- banking's logs: `Reference rate refresh failed` with the cause, and `Bank of Namibia rates unavailable`.
- The source itself: `curl 'https://data-api.ecb.europa.eu/service/data/EXR/D.ZAR.EUR.SP00.A?lastNObservations=3&format=csvdata&detail=dataonly'`.
  The ECB publishes around 16:00 CET on TARGET working days, and sometimes later.

**Fix.**
- **Stale, the ECB failing or late.** Nothing to do while the ECB is down. banking checks every 15 minutes and stores
  the missed days as soon as it answers, and the alert resolves on its own. If the ECB is up but banking cannot reach
  it, look at the `ecb` breaker and banking's outbound network.
- **Stale because the newest rate is quarantined.** Decide the quarantined rate first (below).
- **Quarantined.** Compare the rate with a second source for the same day: a market data screen, the SARB or the Bank
  of Namibia. If it is right (a real move), one operator requests the confirmation with the evidence:
  `POST /api/v1/approvals {"kind":"RATE_CONFIRMATION","referenceRateId":"<id>","reason":"<source and figure>"}`
  with an `Idempotency-Key`. A second operator approves it with `POST /api/v1/approvals/{id}/approval`. If it is
  wrong, the second operator rejects it with `/rejection` and a comment. A rejected rate is never used again, and the
  next day's rate is judged against the last confirmed one.

**Escalate** to the finance lead if two operators cannot agree on the rate, or the peg check fails on a real Bank of
Namibia rate: that would mean the NAD/ZAR peg itself has changed, and FX pricing needs a new decision.
