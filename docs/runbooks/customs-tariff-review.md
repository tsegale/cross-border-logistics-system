# Customs tariff review overdue

Alerts: CustomsTariffReviewOverdue

**What it means.** Duty and import VAT estimates (ADR 0035) come from a dated subset of the EU's TARIC or the SACU
tariff, loaded by a migration with a review date: TARIC before the Combined Nomenclature changes on 1 January, the
SACU tariff every three months. Past that date the rates may have changed. Quotes still work, but they may quote
wrong duties.

**Check.** [Money](http://127.0.0.1:3000/d/money), "Customs tariff review": days left per market. In the database:
`SELECT market, reference_date, review_by FROM customs_tariff_sources ORDER BY market, reference_date;`

**Fix.** Read the current rates for every code the subset holds (docs/tariffs/README.md):
- EU: `python3 scripts/fetch_taric.py --date <today> --origin NA <codes...> > docs/tariffs/taric-<today>-origin-NA.json`.
- SACU: the newest Schedule 1 Part 1 from SARS, with its date and the PDF's SHA-256.

Review the differences. Then add a new logistics migration with a new `customs_tariff_sources` row (new reference
and review dates) and all its lines, even unchanged ones. Old rows stay: earlier quotes point at them. If nothing
changed, the new source still records that someone checked, and when.

**Escalate** to the customs lead if a code's rate changed in a way that affects quotes already issued.
