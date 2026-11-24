-- Daily reference exchange rates from a primary source (ADR 0034): the ECB's euro reference rates. One row per source,
-- currency pair and value date, never overwritten: a correction arrives as a rate that an operator confirms. A rate
-- that fails the sanity checks is QUARANTINED and used only once two operators confirm it (ADR 0021).
CREATE TABLE reference_rates (
    id                  UUID PRIMARY KEY,
    source              VARCHAR(32)   NOT NULL,
    base_currency       VARCHAR(3)    NOT NULL,
    quote_currency      VARCHAR(3)    NOT NULL,
    -- Units of quote_currency for one base_currency, as the source publishes it.
    rate                NUMERIC(19, 8) NOT NULL,
    value_date          DATE          NOT NULL,
    fetched_at          TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    -- The confirmed rate before it, which the day-on-day move is measured against, and the second source's rate for
    -- the same pair and day (the Bank of Namibia's NAD per EUR, for ZAR under the CMA peg), when it had one.
    previous_rate       NUMERIC(19, 8),
    cross_check_source  VARCHAR(32),
    cross_check_rate    NUMERIC(19, 8),
    -- Why it was quarantined: every check it failed.
    quarantine_reason   VARCHAR(500),
    decided_at          TIMESTAMPTZ,
    decided_approval_id UUID,
    CONSTRAINT uq_reference_rates UNIQUE (source, base_currency, quote_currency, value_date),
    CONSTRAINT chk_reference_rates_currencies CHECK (base_currency ~ '^[A-Z]{3}$' AND quote_currency ~ '^[A-Z]{3}$'
        AND base_currency <> quote_currency),
    CONSTRAINT chk_reference_rates_rate CHECK (rate > 0),
    CONSTRAINT chk_reference_rates_status CHECK (status IN ('CONFIRMED', 'QUARANTINED', 'REJECTED')),
    CONSTRAINT chk_reference_rates_quarantine CHECK (status = 'CONFIRMED' OR quarantine_reason IS NOT NULL),
    CONSTRAINT chk_reference_rates_decided CHECK ((decided_at IS NULL) = (decided_approval_id IS NULL)),
    CONSTRAINT chk_reference_rates_cross_check CHECK ((cross_check_source IS NULL) = (cross_check_rate IS NULL)
        AND (cross_check_rate IS NULL OR cross_check_rate > 0) AND (previous_rate IS NULL OR previous_rate > 0))
);
CREATE INDEX idx_reference_rates_latest ON reference_rates (quote_currency, status, value_date DESC);

-- Never overwritten, never deleted: only a quarantined rate's decision may be recorded, once.
CREATE FUNCTION reference_rates_append_only() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'reference rates are never deleted (ADR 0034)';
    END IF;
    IF NEW.id <> OLD.id OR NEW.source <> OLD.source OR NEW.base_currency <> OLD.base_currency
            OR NEW.quote_currency <> OLD.quote_currency OR NEW.rate <> OLD.rate OR NEW.value_date <> OLD.value_date
            OR NEW.fetched_at <> OLD.fetched_at OR NEW.previous_rate IS DISTINCT FROM OLD.previous_rate
            OR NEW.cross_check_source IS DISTINCT FROM OLD.cross_check_source
            OR NEW.cross_check_rate IS DISTINCT FROM OLD.cross_check_rate
            OR NEW.quarantine_reason IS DISTINCT FROM OLD.quarantine_reason THEN
        RAISE EXCEPTION 'a reference rate is never overwritten (ADR 0034)';
    END IF;
    IF OLD.status <> 'QUARANTINED' OR NEW.status NOT IN ('CONFIRMED', 'REJECTED') THEN
        RAISE EXCEPTION 'only a quarantined rate is decided, once (ADR 0034)';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_reference_rates_append_only
    BEFORE UPDATE OR DELETE ON reference_rates
    FOR EACH ROW EXECUTE FUNCTION reference_rates_append_only();

-- Every quote and cross-currency hold records exactly which rate it used: its source, its value date and, for a rate
-- from a source, the row. The NAD/ZAR peg (Common Monetary Area) is a source without a row.
ALTER TABLE fx_quotes
    ADD COLUMN rate_source       VARCHAR(32),
    ADD COLUMN rate_value_date   DATE,
    ADD COLUMN reference_rate_id UUID REFERENCES reference_rates (id);
UPDATE fx_quotes SET rate_source = 'static-configuration';
ALTER TABLE fx_quotes ALTER COLUMN rate_source SET NOT NULL;

ALTER TABLE holds
    ADD COLUMN rate_source       VARCHAR(32),
    ADD COLUMN rate_value_date   DATE,
    ADD COLUMN reference_rate_id UUID REFERENCES reference_rates (id);
UPDATE holds SET rate_source = 'static-configuration' WHERE fx_rate IS NOT NULL;
ALTER TABLE holds ADD CONSTRAINT chk_holds_rate_source CHECK ((fx_rate IS NULL) = (rate_source IS NULL));

-- Four eyes for a quarantined rate (ADR 0021): one operator asks for it to be confirmed, another decides.
ALTER TABLE approval_requests ADD COLUMN reference_rate_id UUID REFERENCES reference_rates (id);
ALTER TABLE approval_requests ALTER COLUMN kind TYPE VARCHAR(32);
ALTER TABLE approval_requests DROP CONSTRAINT chk_approval_kind;
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_kind
    CHECK (kind IN ('DEPOSIT', 'REVERSAL', 'RATE_CONFIRMATION'));
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_rate_confirmation CHECK (kind <> 'RATE_CONFIRMATION'
    OR (reference_rate_id IS NOT NULL AND reason IS NOT NULL AND account_id IS NULL AND amount IS NULL
        AND journal_entry_id IS NULL));
-- A confirmation posts no entry; deposits and reversals still post exactly when approved.
ALTER TABLE approval_requests DROP CONSTRAINT chk_approval_posted;
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_posted CHECK (CASE WHEN kind = 'RATE_CONFIRMATION'
    THEN posted_entry_id IS NULL ELSE (status = 'APPROVED') = (posted_entry_id IS NOT NULL) END);
-- One open confirmation per rate.
CREATE UNIQUE INDEX uq_approval_requests_pending_rate ON approval_requests (reference_rate_id)
    WHERE kind = 'RATE_CONFIRMATION' AND status = 'PENDING';
ALTER TABLE approval_requests ADD CONSTRAINT chk_approval_rate_only CHECK (kind = 'RATE_CONFIRMATION'
    OR reference_rate_id IS NULL);
ALTER TABLE reference_rates ADD CONSTRAINT fk_reference_rates_approval
    FOREIGN KEY (decided_approval_id) REFERENCES approval_requests (id);
