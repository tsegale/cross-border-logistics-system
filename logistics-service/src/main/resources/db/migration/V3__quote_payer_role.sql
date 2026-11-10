-- Incoterms say who pays which leg; the quote now records who is asking (ADR 0008, Phase 2 revision).
-- Import customs clearance becomes its own billable leg with a brokerage fee per lane.

ALTER TABLE tariffs ADD COLUMN import_clearance_fee NUMERIC(19, 4) NOT NULL DEFAULT 0;
UPDATE tariffs SET import_clearance_fee = CASE
    WHEN destination_unlocode LIKE 'DE%' THEN 55.0000
    ELSE 40.0000
END;
ALTER TABLE tariffs ALTER COLUMN import_clearance_fee DROP DEFAULT;
ALTER TABLE tariffs ADD CONSTRAINT chk_tariffs_import_clearance_fee CHECK (import_clearance_fee >= 0);

-- Phase 1 quotes billed EXW and FCA to the buyer and DAP and DDP to the seller; record that for existing rows.
ALTER TABLE freight_quotes ADD COLUMN payer_role VARCHAR(16);
UPDATE freight_quotes SET payer_role = CASE WHEN incoterm IN ('EXW', 'FCA') THEN 'CONSIGNEE' ELSE 'SHIPPER' END;
ALTER TABLE freight_quotes ALTER COLUMN payer_role SET NOT NULL;
ALTER TABLE freight_quotes ADD CONSTRAINT chk_quotes_payer_role CHECK (payer_role IN ('SHIPPER', 'CONSIGNEE'));

ALTER TABLE freight_quotes ADD COLUMN import_clearance_charge NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE freight_quotes ALTER COLUMN import_clearance_charge DROP DEFAULT;
ALTER TABLE freight_quotes ADD CONSTRAINT chk_quotes_charges_non_negative CHECK (
    base_freight_charge >= 0
    AND fuel_surcharge >= 0
    AND origin_handling_charge >= 0
    AND destination_handling_charge >= 0
    AND import_clearance_charge >= 0
);
-- The total is exactly the sum of its legs. NOT VALID: quotes written under V1 included a duty that V2 dropped,
-- so their totals are larger; PostgreSQL still checks every row inserted from now on.
ALTER TABLE freight_quotes ADD CONSTRAINT chk_quotes_total_is_sum CHECK (
    total_amount = base_freight_charge + fuel_surcharge + origin_handling_charge
                   + destination_handling_charge + import_clearance_charge
) NOT VALID;
