-- Import duty is levied on the customs value of the goods (CIF: goods + insurance + freight), which a freight
-- quote does not know. V1 charged tariffs.import_duty_percent on freight + fuel + origin handling, which is the
-- wrong base. Quotes no longer contain duties: under DDP they are billed at cost as a disbursement, and Phase 6
-- computes them from HS codes and declared values.

-- A quote that still carries a duty charge is priced wrongly: expire it so nobody can book against it.
-- Its row stays as the record of what was quoted.
UPDATE freight_quotes
SET valid_until = LEAST(valid_until, now())
WHERE import_customs_duty_charge > 0;

ALTER TABLE freight_quotes DROP COLUMN import_customs_duty_charge;

-- Dropping the column also drops chk_tariffs_positive_rates, which referenced it; recreate it without.
ALTER TABLE tariffs DROP COLUMN import_duty_percent;
ALTER TABLE tariffs ADD CONSTRAINT chk_tariffs_positive_rates CHECK (
    rate_per_unit > 0
    AND minimum_charge >= 0
    AND fuel_surcharge_percent >= 0
    AND origin_handling_fee >= 0
    AND destination_handling_fee >= 0
    AND transit_days_min > 0
    AND transit_days_max >= transit_days_min
);
