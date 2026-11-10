-- Logistics no longer converts currencies (Phase 2): banking-service owns FX. A quote is priced in its tariff
-- currency; a figure in any other currency is indicative, comes from banking's /fx-rates and is stored as shown.
-- snapshot_fx_rate stays for quotes priced in another currency before V4; it is 1 for every quote from now on.

ALTER TABLE freight_quotes
    ADD COLUMN indicative_currency     VARCHAR(3),
    ADD COLUMN indicative_total_amount NUMERIC(19, 4),
    ADD COLUMN indicative_rate         NUMERIC(19, 8),
    ADD COLUMN indicative_rate_as_of   TIMESTAMPTZ,
    ADD COLUMN indicative_rate_source  VARCHAR(64);

-- Either no indicative figure was asked for, or it was asked for and is complete, or banking could not be
-- reached and only the requested currency is recorded.
ALTER TABLE freight_quotes ADD CONSTRAINT chk_quotes_indicative CHECK (
    (indicative_currency IS NULL
        AND indicative_total_amount IS NULL AND indicative_rate IS NULL
        AND indicative_rate_as_of IS NULL AND indicative_rate_source IS NULL)
    OR (indicative_currency IS NOT NULL
        AND indicative_currency <> currency
        AND ((indicative_total_amount IS NULL AND indicative_rate IS NULL
                AND indicative_rate_as_of IS NULL AND indicative_rate_source IS NULL)
            OR (indicative_total_amount > 0 AND indicative_rate > 0
                AND indicative_rate_as_of IS NOT NULL AND indicative_rate_source IS NOT NULL)))
);
