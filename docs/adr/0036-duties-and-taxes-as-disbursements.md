# 36. Duties and import VAT are disbursements in the ledger, never revenue

Date: 2026-11-24 · Status: Accepted · Builds on ADR 0004 (double entry), ADR 0011 (holds), ADR 0035 (the estimate)

## Context
When FreightFlow collects duties and import VAT with the freight (DDP, or the consignee paying both), the money is
not the forwarder's. It is owed to the customs authority: the Hauptzollamt for imports into Germany, NamRA for
imports into Namibia. Booking it to FREIGHT_REVENUE would overstate revenue and hide a debt. The amount collected is
also an estimate: customs assesses the real amount at import, weeks after the hold was taken and captured at pickup.

## Decision
**A liability account per currency, `CUSTOMS_DISBURSEMENTS`** (banking V12), a system account like CLEARING and
FX_POSITION, with its balance shards (ADR 0010). Its balance is what FreightFlow owes customs.

**The capture splits in one entry.** `POST /holds/{id}/capture` takes `disbursementAmount`, the part of the captured
amount that is duties and taxes. The entry debits the payer (through FX_POSITION for a cross-currency hold, at the
locked rate, as before) and credits FREIGHT_REVENUE with the freight and CUSTOMS_DISBURSEMENTS with the duties and
taxes, so one balanced entry shows both. The hold records `captured_disbursement_amount`. A disbursement larger than
the capture is refused, and no disbursement means the capture is all freight, as before.

**The settlement pays customs and settles the difference, in one entry.** `POST /holds/{id}/customs-settlement`
(internal, logistics only, ADR 0015) takes the assessed amount and the authority's reference. In the charge
currency C:

    CUSTOMS_DISBURSEMENTS[C]   - estimate     the liability from the capture is paid off
    CLEARING[C]                + assessed     paid out to the authority
    payer                      estimate - assessed    negative: charged the shortfall; positive: refunded

A cross-currency hold converts the payer leg at the hold's locked rate through FX_POSITION, with the rate on the
entry, the rule for every currency change. A shortfall the payer cannot cover is refused with
`insufficient-funds`; logistics parks the command for an operator (ADR 0012). A hold is settled at most once
(`customs-already-settled`), and only once captured (`hold-not-captured`). The settlement is the record:
`customs_settlements` has the estimate, the assessment, the difference in both currencies, the authority's
reference, the entry and the actor.

**Four eyes in logistics.** The assessed amount comes from the authority's notice, typed in by an operator. Logistics
records it as requested by one operator and confirmed by a second before it sends the settlement (ADR 0021); banking
takes the settlement only from logistics' own token, like every hold command.

## Consequences
- FREIGHT_REVENUE is freight only. The ledger shows duties collected, paid and owed, per currency, at any time.
- The estimate and the assessment can differ, and the customer sees the difference as a separate charge or refund.
- Paying customs is modelled as a CLEARING posting: the outgoing bank payment itself is outside the system, as
  deposits are.
- Duties in a currency other than the freight's (NAD into Namibia while freight is priced in EUR) are collected in a
  hold of their own, in their currency, and settled against it (ADR 0038).
