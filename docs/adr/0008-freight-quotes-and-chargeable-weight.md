# 8. Freight quotes, industry chargeable weight, and Incoterms 2020 allocation

Date: 2026-11-08 · Status: Accepted

## Context
On the Walvis Bay (`NAWVB`) to Hamburg (`DEHAM`) corridor, cargo moves either by less-than-container-load ocean freight (`SEA_LCL`, e.g., frozen hake pallets) or by air freight (`AIR`, e.g., high-value chilled fish or industrial parts from Windhoek `NAWDH` to Frankfurt `DEFRA`). Carriers price cargo on **chargeable weight/volume** rather than raw scale weight so light, bulky cargo pays for the vessel or aircraft space it occupies. In addition, which party (seller vs buyer) pays origin handling, main carriage, destination handling, and import clearance depends on the agreed ICC Incoterms 2020 rule.

If a quote computed rates dynamically on `GET /api/v1/quotes/{id}` by joining live `tariffs`, any later tariff update would retroactively alter a price already quoted to a customer.

## Decision
- **Chargeable weight rules (`ChargeableWeightCalculator`)**:
  - **`AIR` (IATA Resolution 502)**: Volumetric weight is $\text{volume in cm}^3 / 6000$ (`1 CBM = 166.667 kg`). Chargeable weight is $\max(\text{actual kg}, \text{volumetric kg})$, billed per `KG`.
  - **`SEA_LCL` (W/M revenue ton)**: Ocean LCL bills per weight-or-measure (`WM_TON`) revenue ton, defined as $\max(\text{actual weight in metric tons } (\text{kg}/1000),\ \text{volume in m}^3 (\text{cm}^3/10^6))$. Equivalent chargeable weight in kg is $\text{WM\_TON} \times 1000$.
- **Incoterms 2020 cost allocation (revised in Phase 2, migration `V3`)**: An Incoterm says who pays which part of the move; it does not say what the forwarder sells. Phase 1 conflated the two: `EXW` listed `DESTINATION_HANDLING` among the buyer's costs but the quote left it out, because the quote silently assumed a payer per rule. The quote now names the quoting party explicitly (`payerRole`: `SHIPPER` or `CONSIGNEE`) and bills exactly the legs that party pays under the rule:

  | Incoterm | `ORIGIN_HANDLING` | `MAIN_CARRIAGE` (+ fuel) | `DESTINATION_HANDLING` | `IMPORT_CLEARANCE` | Duties and taxes |
  | --- | --- | --- | --- | --- | --- |
  | `EXW` | consignee | consignee | consignee | consignee | consignee |
  | `FCA` | shipper | consignee | consignee | consignee | consignee |
  | `DAP` | shipper | shipper | shipper | consignee | consignee |
  | `DDP` | shipper | shipper | shipper | shipper | shipper, billed at cost as a disbursement |

  - `FCA` is read with the named place at the origin terminal, so delivery to the carrier and export clearance are the seller's. `DAP` is read with destination terminal charges inside the seller's contract of carriage, which is how forwarders on this lane sell door delivery.
  - `IMPORT_CLEARANCE` is the customs brokerage fee (`tariffs.import_clearance_fee`, seeded at 55 EUR into Germany and 40 EUR into Namibia). It is not the duty.
  - `EXW` asked by the shipper and `DDP` asked by the consignee bill nothing: `422 nothing-to-quote`.
  - Existing Phase 1 quotes were given the payer they were implicitly priced for (`EXW`/`FCA` consignee, `DAP`/`DDP` shipper).
  - `IncotermBillableLegsTest` has one test per Incoterm and role.
  - `CHECK (total_amount = sum of the legs)` holds for every new quote (`NOT VALID` only because pre-`V2` rows carried a duty).
- **Immutable tariff snapshotting**:
  - `freight_quotes` snapshots `tariff_currency`, `snapshot_rate_per_unit`, `snapshot_minimum_charge`, `snapshot_fuel_surcharge_pct`, `snapshot_fx_rate`, every computed charge component (`base_freight_charge`, `fuel_surcharge`, `origin_handling_charge`, `destination_handling_charge`, `total_amount`), and `valid_until` (24 hours via injected `Clock`).
  - Updating `tariffs` in PostgreSQL never mutates previously issued quotes (verified in `QuoteIntegrationTest`).

- **No customs duties in quotes (revised in Phase 2, migration `V2`)**: Phase 1 charged `import_duty_percent` on freight + fuel + origin handling. Customs duty is levied on the customs value of the goods (CIF: goods + insurance + freight), which a freight quote does not know, so that number was wrong for every shipment. Quotes now contain freight charges only. Under `DDP` the response states that duties and taxes are paid on the shipper's behalf and billed at cost as a disbursement; under every other rule they are the consignee's. Quotes that carried a duty were expired by `V2` so they cannot be booked. Phase 6 computes duties from HS codes and declared values.

## Consequences
- `POST /api/v1/shipments` can trust `freight_quotes.total_amount`, `currency`, and `valid_until` as an immutable commercial contract without re-querying `tariffs`.
- Expired quotes (`now >= valid_until`) are rejected with `409 quote-expired` when booking a shipment.
