-- Duties and import VAT are not revenue (ADR 0036). The forwarder collects them from the customer and pays them on to
-- the customs authority, so it holds them as a liability, CUSTOMS_DISBURSEMENTS, until they are paid.

ALTER TABLE accounts DROP CONSTRAINT chk_accounts_type;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_type
    CHECK (account_type IN ('CUSTOMER', 'CLEARING', 'FREIGHT_REVENUE', 'FX_POSITION', 'CUSTOMS_DISBURSEMENTS'));

-- One per currency, like every system account (V1); sequence numbers 4 are free, customer numbers start at 1000.
-- Account numbers carry ISO 7064 MOD 97-10 check digits.
INSERT INTO accounts (id, account_number, account_type, currency, status, balance, created_at, updated_at)
VALUES
    ('01930000-0000-7000-8000-000000000010', 'FF06NAD000000004', 'CUSTOMS_DISBURSEMENTS', 'NAD', 'ACTIVE', 0.0000, '2026-11-24T00:00:00Z', '2026-11-24T00:00:00Z'),
    ('01930000-0000-7000-8000-000000000011', 'FF45ZAR000000004', 'CUSTOMS_DISBURSEMENTS', 'ZAR', 'ACTIVE', 0.0000, '2026-11-24T00:00:00Z', '2026-11-24T00:00:00Z'),
    ('01930000-0000-7000-8000-000000000012', 'FF30EUR000000004', 'CUSTOMS_DISBURSEMENTS', 'EUR', 'ACTIVE', 0.0000, '2026-11-24T00:00:00Z', '2026-11-24T00:00:00Z');

-- Their 16 balance shards, as for the other system accounts (V2, ADR 0010).
INSERT INTO account_balance_shards (account_id, shard, balance, updated_at)
SELECT a.id, s.shard, 0.0000, now()
FROM accounts a
CROSS JOIN generate_series(0, 15) AS s(shard)
WHERE a.account_type = 'CUSTOMS_DISBURSEMENTS';

-- A capture credits FREIGHT_REVENUE with the freight and CUSTOMS_DISBURSEMENTS with the collected estimate, in one
-- entry. The part that was a disbursement is recorded on the hold.
ALTER TABLE holds ADD COLUMN captured_disbursement_amount NUMERIC(19, 4);
ALTER TABLE holds ADD CONSTRAINT chk_holds_disbursement CHECK (captured_disbursement_amount IS NULL
    OR (captured_amount IS NOT NULL AND captured_disbursement_amount >= 0
        AND captured_disbursement_amount <= captured_amount));

-- Paying the assessed amount to the authority, and charging or refunding the customer the difference from the
-- estimate, is one more entry per hold.
ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_type;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_type
    CHECK (entry_type IN ('DEPOSIT', 'TRANSFER', 'FX_TRANSFER', 'REVERSAL', 'CAPTURE', 'CUSTOMS_SETTLEMENT'));

CREATE TABLE customs_settlements (
    id                   UUID PRIMARY KEY,
    -- At most one settlement per captured hold.
    hold_id              UUID          NOT NULL UNIQUE REFERENCES holds (id),
    currency             VARCHAR(3)    NOT NULL,
    estimate             NUMERIC(19, 4) NOT NULL,
    assessed             NUMERIC(19, 4) NOT NULL,
    -- assessed - estimate, in the hold's charge currency: positive was charged to the customer, negative refunded.
    difference           NUMERIC(19, 4) NOT NULL,
    -- The same difference in the payer's currency, at the hold's locked rate.
    payer_difference     NUMERIC(19, 4) NOT NULL,
    authority_reference  VARCHAR(64)   NOT NULL,
    journal_entry_id     UUID          NOT NULL UNIQUE REFERENCES journal_entries (id),
    settled_by_subject   VARCHAR(64)   NOT NULL,
    settled_by_client_id VARCHAR(64)   NOT NULL,
    settled_request_id   VARCHAR(64),
    created_at           TIMESTAMPTZ   NOT NULL,
    CONSTRAINT chk_customs_settlements_amounts CHECK (estimate >= 0 AND assessed >= 0
        AND difference = assessed - estimate AND sign(payer_difference) = sign(difference)),
    CONSTRAINT chk_customs_settlements_currency CHECK (currency ~ '^[A-Z]{3}$')
);
