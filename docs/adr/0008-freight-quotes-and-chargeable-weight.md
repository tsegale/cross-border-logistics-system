# 8. Freight quotes, industry chargeable weight, and Incoterms 2020 allocation

Date: 2026-11-08 · Status: Accepted

## Context
On the Walvis Bay (`NAWVB`) to Hamburg (`DEHAM`) corridor, cargo moves either by less-than-container-load ocean freight (`SEA_LCL`, e.g., frozen hake pallets) or by air freight (`AIR`, e.g., high-value chilled fish or industrial parts from Windhoek `NAWDH` to Frankfurt `DEFRA`). Carriers price cargo on **chargeable weight/volume** rather than raw scale weight so light, bulky cargo pays for the vessel or aircraft space it occupies. In addition, which party (seller vs buyer) pays origin handling, main carriage, destination handling, and import clearance depends on the agreed ICC Incoterms 2020 rule.

If a quote computed rates dynamically on `GET /api/v1/quotes/{id}` by joining live `tariffs`, any later tariff update would retroactively alter a price already quoted to a customer.

## Decision
- **Chargeable weight rules (`ChargeableWeightCalculator`)**:
  - **`AIR` (IATA Resolution 502)**: Volumetric weight is $\text{volume in cm}^3 / 6000$ (`1 CBM = 166.667 kg`). Chargeable weight is $\max(\text{actual kg}, \text{volumetric kg})$, billed per `KG`.
  - **`SEA_LCL` (W/M revenue ton)**: Ocean LCL bills per weight-or-measure (`WM_TON`) revenue ton, defined as $\max(\text{actual weight in metric tons } (\text{kg}/1000),\ \text{volume in m}^3 (\text{cm}^3/10^6))$. Equivalent chargeable weight in kg is $\text{WM\_TON} \times 1000$.
- **Incoterms 2020 cost allocation (`Incoterm`)**:
  - `EXW` (Ex Works): Quote includes main carriage + fuel surcharge; buyer bears origin pickup, main carriage, destination handling, and import clearance.
  - `FCA` (Free Carrier): Seller delivers cleared for export to carrier at named place; quote includes main carriage + fuel surcharge.
  - `DAP` (Delivered at Place): Seller bears origin handling, main carriage + fuel surcharge, and destination terminal handling/delivery; buyer bears import customs & duties.
  - `DDP` (Delivered Duty Paid): Seller bears all charges from origin through destination delivery and import clearance.
- **Immutable tariff snapshotting**:
  - `freight_quotes` snapshots `tariff_currency`, `snapshot_rate_per_unit`, `snapshot_minimum_charge`, `snapshot_fuel_surcharge_pct`, `snapshot_fx_rate`, every computed charge component (`base_freight_charge`, `fuel_surcharge`, `origin_handling_charge`, `destination_handling_charge`, `total_amount`), and `valid_until` (24 hours via injected `Clock`).
  - Updating `tariffs` in PostgreSQL never mutates previously issued quotes (verified in `QuoteIntegrationTest`).

- **No customs duties in quotes (revised in Phase 2, migration `V2`)**: Phase 1 charged `import_duty_percent` on freight + fuel + origin handling. Customs duty is levied on the customs value of the goods (CIF: goods + insurance + freight), which a freight quote does not know, so that number was wrong for every shipment. Quotes now contain freight charges only. Under `DDP` the response states that duties and taxes are paid on the shipper's behalf and billed at cost as a disbursement; under every other rule they are the consignee's. Quotes that carried a duty were expired by `V2` so they cannot be booked. Phase 6 computes duties from HS codes and declared values.

## Consequences
- `POST /api/v1/shipments` can trust `freight_quotes.total_amount`, `currency`, and `valid_until` as an immutable commercial contract without re-querying `tariffs`.
- Expired quotes (`now >= valid_until`) are rejected with `409 quote-expired` when booking a shipment.
