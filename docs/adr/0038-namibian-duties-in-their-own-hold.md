# 38. Duties into Namibia are collected in NAD, in a hold of their own

Date: 2026-11-25 · Status: Accepted · Builds on ADR 0011 (holds), ADR 0012 (payment saga), ADR 0035 (the estimate),
ADR 0036 (disbursements)

## Context
Into Walvis Bay, the duties and Namibian import VAT are estimated in NAD (ADR 0035: the SACU tariff on the FOB value,
VAT 15 % of FOB plus 10 %), and NamRA is owed NAD. The corridor's freight is priced in EUR. Until now FreightFlow
quoted those duties but did not collect them: the freight hold is in one currency, and the ADR 0036 capture splits
one amount in that currency into revenue and disbursement. The consignee's broker paid NamRA, and the forwarder could
not offer a door-to-door price that includes them.

This ADR adds no fiscal rule: what is owed, to whom and in which currency is ADR 0035's, with its sources (the SACU
Schedule No. 1 Part 1 of 2026-11-13, NamRA's valuation booklet, the Value-Added Tax Act 10 of 2000, consolidated
text of 2024-10-01). It decides how the ledger collects an amount in another currency than the freight's.

## Options
1. **A second hold, in NAD, on the payer's account.** Banking already holds an amount in a charge currency on an
   account in another: it locks its customer rate at authorisation and captures through FX_POSITION at that rate
   (ADR 0011). A NAD hold on a NAD account converts nothing.
2. **The NAD duties converted into the freight hold at the hold's locked rate**, credited to
   `CUSTOMS_DISBURSEMENTS[NAD]`. One hold, but the hold's rate is the freight's (EUR to the payer's currency); the
   duties need a NAD rate too, so one capture entry would carry two rates, which the ledger's one-rate-per-entry rule
   (CLAUDE.md, ADR 0004) does not allow, and banking's hold, capture and settlement would all change.
3. **Not collecting them** (the current state).

## Decision
**Option 1.** Each hold stays in one currency, banking's hold, capture and settlement code is used as it is, and the
FX, if any, is banking's, at its rate, recorded on its entry. Logistics gains a second hold per shipment.

- **When.** A quote collects the duties when the quoting party owes them (the Incoterm says who). In the freight
  currency they go into the freight hold, as before (ADR 0036). In another, and above zero, they are held on their own:
  `customs.collectedInOwnHold` on the quote, `payment.dutyHold` on the shipment. `amountDueOnBooking` stays the
  freight currency's amount.
- **Order.** The duty hold is authorised first, then the freight hold, with the same requested expiry. The freight
  hold's acceptance still books the shipment, as in ADR 0012, so a booked shipment always has both. A refused duty
  hold fails the booking with nothing held. A refused freight hold releases the duty hold. A cancellation while either
  is in flight releases whatever stands.
- **Lifecycle.** The duty hold follows the freight hold: extended by the renewal job to the same target, re-authorised
  if banking expires it (ADR 0028), captured at pickup and released on cancellation. Its capture is all disbursement
  (`disbursementAmount` = the amount): banking credits `CUSTOMS_DISBURSEMENTS[NAD]`, nothing to revenue.
- **Commands.** `shipment_payments.hold_kind` (FREIGHT or DUTIES) says which hold a command is for. The duty hold's
  idempotency keys say so (`shipment-<id>-duties-authorise`), and the unique indexes (one command of a type, one open
  extension, one open re-authorisation) are per hold.
- **Settlement.** NamRA's assessment is recorded and confirmed with four eyes in NAD, as in ADR 0036, and settled
  against the duty hold: banking pays `CUSTOMS_DISBURSEMENTS[NAD]` out and charges or refunds the difference.
- **Refusals.** A refused capture, release, extension or re-authorisation of the duty hold marks the duty hold FAILED
  and leaves the command FAILED for an operator; the freight's payment status is not touched.

## FX risk
- **Payer with a NAD account.** None: NAD held, NAD captured, NAD owed.
- **Payer with a EUR (or ZAR) account.** Banking locks its customer rate when the duty hold is authorised (ADR 0011).
  - From booking to pickup, the payer's amount is fixed. The forwarder carries the NAD/EUR move on the estimate for up
    to 120 days, as for every cross-currency hold.
  - At capture, the payer's EUR goes through FX_POSITION at the locked rate, and `CUSTOMS_DISBURSEMENTS[NAD]` is
    exactly the NAD estimate. From then until NamRA is paid, the forwarder owes NAD and holds the EUR it received: an
    open FX_POSITION the forwarder's treasury closes when it buys NAD. ZAR is pegged to NAD 1:1 in the Common
    Monetary Area (ADR 0034), so a ZAR payer's position carries no NAD risk.
  - At settlement, the difference between the assessment and the estimate goes through the hold's locked rate (ADR
    0036), so the payer pays or gets back the difference at the rate they agreed to, and the forwarder carries the
    rate move on the difference too.
- Customs usually assesses within weeks of import. Pricing the risk (a margin on the rate, or hedging FX_POSITION) is
  a business decision outside this ADR.

## Consequences
- A door-to-door price into Namibia can include the duties, held in NAD.
- Two holds per shipment: twice the banking calls, and two holds to release if the shipment is cancelled.
- The ledger shows NAD owed to NamRA in `CUSTOMS_DISBURSEMENTS[NAD]` the same way it shows EUR owed to the
  Hauptzollamt.
- Shipment events do not carry the duty hold's status: notifications are about the shipment and its freight payment,
  as before.
- Tested in `CustomsFlowIntegrationTest`: a NAD duty hold authorised before the EUR freight hold, both captured at
  pickup, NamRA's assessment settled against the duty hold; a refused duty hold; a refused freight hold releasing the
  duty hold.
