# 35. Duties and import VAT: an estimate from dated tariff subsets, with the EU-SADC EPA preference

Date: 2026-11-24 · Status: Accepted · Builds on ADR 0008 (Incoterms and payer roles), ADR 0034 (rates); ADR 0036 does
the accounting

## Context
A forwarder on the Walvis Bay to Hamburg corridor is asked what the goods will cost landed, not only what the freight
costs. Phase 2 left duties out of quotes because the base was wrong (V2): duties are on the goods' customs value, not
on the freight. Namibian goods can enter the EU duty-free under the EU-SADC Economic Partnership Agreement, but only
with a valid proof of origin, so the same consignment can cost very different amounts.

## Sources, checked 2026-11-24
Each rule below was read in its primary source on 2026-11-24.
- **EU customs value.** CIF at the EU border. Union Customs Code, Regulation (EU) No 952/2013: Article 70 (the
  transaction value) and Article 71(1)(e) (the costs of transport and insurance up to the place where the goods enter
  the Union are added).
- **EU duty rates: TARIC.** Read through `scripts/fetch_taric.py`, reference date 2026-11-24, last TARIC update
  2026-11-23, for origin NA. The fetcher reads the same files the consultation pages load, because TARIC has no
  public API. Output and table: `docs/tariffs/`.
  - Third-country duties (measure type 103): Cape hake fillets 0304 74 11 00, 7.50 % (R1006/11); dates
    0804 10 00 91/99, 7.70 % (R2204/99); wood charcoal 4402 90 00 90, 0 %; boneless beef 0201 30 00 90,
    12.80 % + 303.40 EUR / 100 kg (R2204/99).
  - Tariff preference (type 142) for the SADC EPA group: 0 % for all five, under Council Decision (EU) 2016/1623
    (D1623/16), from 10 October 2016 (beef from 6 November 2016).
- **Proof of origin.** EU-SADC EPA, Protocol 1 (OJ L 250, 16.9.2016), Title V, all read in the EUR-Lex text.
  - Article 19: a movement certificate EUR.1, or an origin declaration on an invoice, a delivery note or another
    commercial document.
  - Article 24(1): an origin declaration may be made out by an approved exporter, or by any exporter for a
    consignment of originating products worth no more than EUR 6 000.
  - Article 26(1): a proof is valid for ten months from issue.
  - Article 29: small private packages and travellers' luggage are exempt, up to EUR 500 and EUR 1 200; freight
    never is.
  - Annex IV: the wording of the declaration.
- **German import VAT (Einfuhrumsatzsteuer).**
  - § 21(2) UStG: the customs rules apply.
  - § 11 UStG, the base: Absatz 1, the customs value; Absatz 3 Nr. 2, plus the duties; Absatz 3 Nr. 3, plus the
    costs of transport and other services up to the first destination in the Community.
  - § 12 UStG, the rate: 19 % (Absatz 1), or 7 % for the goods of Anlage 2 (Absatz 2 Nr. 1). Anlage 2 has fish
    (Nr. 3, Kapitel 3), meat (Nr. 2, Kapitel 2) and fruit (Nr. 11, Positionen 0801 to 0813), but not wood charcoal.
- **The EU's EUR 150 relief.** It ended on 1 July 2026 (Council Regulation (EU) 2026/382). TARIC shows the EUR 3
  low-value consignment duty and a EUR 2 Union handling fee from 1 November 2026, for low-value consignments to
  consumers. A freight forwarder's consignments are not those, so neither is modelled.
- **SACU duties.** The SACU common external tariff, which Namibia applies, is published by SARS as Schedule No. 1
  Part 1, dated 2026-11-13, columns General and EU / UK (the EPA). The subset's figures and the PDF's SHA-256 are in
  `docs/tariffs/`. NamRA's customs valuation booklet says freight and insurance are deducted from the invoice
  "as we use FOB": SACU's customs value is FOB.
- **Namibian import VAT.** Value-Added Tax Act 10 of 2000, read in the NamibLII consolidated text of 2024-10-01.
  - Section 6(1)(b): the rate, 15 per cent.
  - Section 12(2)(a): the base, the greater of the FOB value plus 10 per cent of it, or the open market value.
    Unlike South Africa's rule, duty is not added.

## Decision
- **Goods on the quote.** Each line has a tariff code, a description, the origin country, a quantity, the net
  weight, a customs value in a currency, and an optional proof of origin: type, reference, issue date, and approved
  exporter number.
- **Codes are checked twice.** The format comes first: 10 digits into the EU, 8 into Namibia (`invalid-hs-code`).
  Then the code must be in the loaded subset (`unknown-tariff-code`), so a code that is not in it gets no estimate.
- **The market comes from the destination country.** Germany is the EU; Namibia is SACU. Other destinations are
  refused (`customs-market-unsupported`).
- **Estimated in the market's currency**: EUR into the EU, NAD into Namibia.
  - Declared values in another currency are converted at banking's mid rate, which is an estimate and carries no
    spread (ADR 0034).
  - If no rate is available, the quote is refused (`customs-conversion-unavailable`): an estimate on a missing rate
    would be a guess.
- **Into the EU, per line** (`CustomsEstimator`):
  - The customs value is the goods plus the freight to Hamburg. Origin handling and main carriage, for the whole
    move whoever pays them, are shared between the lines by net weight.
  - The duty is the third-country rate (ad valorem plus any specific amount per 100 kg net), or the EPA preference
    when the proof holds. The proof must be for an origin the preference covers (NA in this subset). It must still be
    valid on the expected arrival date, today plus the longest transit. A declaration over EUR 6 000 of originating
    goods must carry an approved exporter's number.
  - Import VAT is charged on the customs value, plus the duty, plus destination handling and import clearance.
- **Into Namibia, per line**:
  - The customs value is FOB, the goods' value alone. The duty is the General rate, or the EU / UK rate for EU goods
    with a valid proof (the same Protocol 1 applies both ways).
  - VAT is 15 % of FOB plus 10 %. The open market value is unknown to a quote, which the estimate says.
- **Rounding.** Every amount is rounded HALF_EVEN to the currency's minor units, per line, and the totals are sums.
  The lines keep the rate, the customs value, the duty rate as text, whether the preference applied and why, the VAT
  base and the VAT.
- **The quote shows it apart from the freight**, labelled an estimate, with the tariff source and date. It also says
  who owes it: the shipper under DDP, the consignee otherwise (ADR 0008). FreightFlow collects it with the freight
  only when the party quoting owes it and it is in the freight's currency (EUR into Hamburg). Then the booking holds
  freight plus estimate, and the capture and settlement keep them apart (ADR 0036).
- **Tariff data** is a dated subset per market, loaded by a reviewed migration (V13). Each subset records its
  source, URL, reference date, the day it was read, a review date and its evidence file. A new subset is a new
  migration, and quotes keep pointing at the source they used. `CustomsTariffReviewOverdue` opens a ticket once a
  review date has passed. The EU's date is 2026-12-31, before the Combined Nomenclature changes on 1 January.

## Consequences
- The worked example (README) quotes a Namibian hake consignment duty-free with the declaration, and at 7.5 %
  without it.
- Ten codes are covered, enough for the corridor's demo goods. A real deployment loads the full TARIC and SACU
  tariffs from their bulk files with the same review.
- Not modelled: quotas, entry prices and other conditional measures (TARIC lists them per code; the subset records
  them in its evidence file), anti-dumping duties, excise (wine into Namibia), the open market value, and insurance in
  the CIF value (none is declared).
- Duties into Namibia are in NAD while the corridor's freight is priced in EUR, so they are quoted but not collected:
  the consignee's broker pays NamRA. Collecting them would need a second hold in NAD.
