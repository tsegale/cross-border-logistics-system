# Tariff data

The duty and import VAT rates FreightFlow estimates with (ADR 0035) are a dated subset of the authoritative tariffs,
loaded by a reviewed Flyway migration in logistics-service. Neither tariff has a public API. Each subset records its
source, the day it was read and a review date, and the `CustomsTariffStale` alert fires once the review date has
passed.

## EU: TARIC, imports into Germany (Hamburg)

- Source: the EU's TARIC consultation, read by `scripts/fetch_taric.py`, which loads the same files the consultation
  pages do. Reference date 2026-11-24, origin NA (Namibia), last TARIC update 23-11-2026. The output is
  [`taric-2026-11-24-origin-NA.json`](taric-2026-11-24-origin-NA.json): per code, the description path, every measure
  line, the third-country duty (measure type 103) and the tariff preference (type 142).
- The preferences cite Council Decision (EU) 2016/1623 (TARIC `D1623/16`), the EU-SADC EPA. They are 0% for all five
  codes, from 10 October 2016 (beef from 6 November 2016).
- Review by 2026-12-31: the Combined Nomenclature is replaced every 1 January.

| TARIC code | Goods | Third-country duty | Legal base | SADC EPA | Import VAT (§ 12 UStG) |
| --- | --- | --- | --- | --- | --- |
| 0304 74 11 00 | Frozen fillets of Cape hake and deepwater hake (Merluccius capensis, M. paradoxus) | 7.50 % | R1006/11 | 0 % | 7 % (Anlage 2 Nr. 3) |
| 0804 10 00 91 | Dates, in immediate containers of 35 kg or less | 7.70 % | R2204/99 | 0 % | 7 % (Anlage 2 Nr. 11) |
| 0804 10 00 99 | Dates, other | 7.70 % | R2204/99 | 0 % | 7 % (Anlage 2 Nr. 11) |
| 4402 90 00 90 | Wood charcoal, other | 0 % | R2658/87 | 0 % | 19 % (not in Anlage 2) |
| 0201 30 00 90 | Fresh or chilled boneless bovine meat, other | 12.80 % + 303.40 EUR / 100 kg | R2204/99 | 0 % | 7 % (Anlage 2 Nr. 2) |

Measures the subset does not model, as TARIC lists them, are in the JSON: controls (veterinary, organic, illegal
fishing), the low-value consignment duty of EUR 3 and the Union handling fee of EUR 2 (both from 1 November 2026,
for low-value consignments only, not for freight), and an autonomous end-use suspension for dates.

## SACU: Namibia (Walvis Bay)

- Source: South African Revenue Service, Schedule No. 1 Part 1 to the Customs and Excise Act, chapters 1 to 99,
  dated 2026-11-13. SACU's common external tariff, which Namibia applies. It was published at
  https://www.sars.gov.za/legal-lprim-ce-sch1p1chpt1-to-99-schedule-no-1-part-1-chapters-1-to-99/, read 2026-11-24.
  SHA-256 of the PDF read: `68e9acf6131b73a90d3dd88c3a092fa5726aa93ecb1653629a850621c1f90795`. The columns are
  General, EU / UK (the EU-SADC EPA), EFTA, SADC, MERCOSUR and AfCFTA.
- Review by 2027-02-13: the schedule is amended several times a year.

| Tariff heading | Goods | General | EU / UK | Namibian VAT |
| --- | --- | --- | --- | --- |
| 8418.10.20 | Combined refrigerator-freezers, total capacity not exceeding 400 l | 25 % | free | 15 % |
| 8429.51.20 | Front-end shovel loaders, not tracked, 3 000 to 30 000 kg (excluding those for mines) | 10 % | free | 15 % |
| 2204.21.41 | Unfortified wine, 4.5 % to 16.5 % vol, in containers of 2 l or less | 25 % | free | 15 % |
| 8471.30.90 | Portable automatic data processing machines, other | free | free | 15 % |

Excise duties (Schedule 1 Part 2, for wine among others) are not in the subset.

## Updating

Run the fetcher, or read the new schedule, review the differences, and add a new migration with the new rows and
dates. The old rows stay as the record of what earlier quotes used. Never edit a migration that is already on main.
