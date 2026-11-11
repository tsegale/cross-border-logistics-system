-- Card-style holds for freight charges (ADR 0011): authorise, then capture or release.
--
-- A hold is not money movement, so it is not a posting. It reserves part of a customer's balance: the hold row
-- records the reservation and accounts.held_amount caches the sum of the account's active holds, updated under
-- the same row lock as the balance. available balance = balance - held_amount, and the database refuses any
-- write that would make it negative. Capture posts one journal entry; release only frees the reservation.

ALTER TABLE accounts ADD COLUMN held_amount NUMERIC(19, 4) NOT NULL DEFAULT 0.0000;
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_held_non_negative CHECK (held_amount >= 0);
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_system_holds_nothing
    CHECK (account_type = 'CUSTOMER' OR held_amount = 0);
-- The customer never overdraws through holds: holds never exceed the balance, and no debit may go below them.
ALTER TABLE accounts ADD CONSTRAINT chk_accounts_customer_available_non_negative
    CHECK (account_type <> 'CUSTOMER' OR balance >= held_amount);

CREATE TABLE holds (
    id                       UUID PRIMARY KEY,
    payer_account_id         UUID           NOT NULL REFERENCES accounts (id),
    reference                VARCHAR(160)   NOT NULL,
    -- What is charged, in the charge currency (the freight quote's currency) ...
    amount                   NUMERIC(19, 4) NOT NULL,
    currency                 VARCHAR(3)     NOT NULL,
    -- ... and what is reserved on the payer account, in its currency, at the rate locked when the hold was made.
    payer_amount             NUMERIC(19, 4) NOT NULL,
    payer_currency           VARCHAR(3)     NOT NULL,
    fx_rate                  NUMERIC(19, 8),
    fx_mid_rate              NUMERIC(19, 8),
    fx_spread                NUMERIC(10, 6),
    status                   VARCHAR(16)    NOT NULL,
    captured_amount          NUMERIC(19, 4),
    captured_payer_amount    NUMERIC(19, 4),
    capture_journal_entry_id UUID UNIQUE REFERENCES journal_entries (id),
    expires_at               TIMESTAMPTZ    NOT NULL,
    closed_at                TIMESTAMPTZ,
    created_at               TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_holds_status CHECK (status IN ('ACTIVE', 'CAPTURED', 'RELEASED', 'EXPIRED')),
    CONSTRAINT chk_holds_positive CHECK (amount > 0 AND payer_amount > 0),
    CONSTRAINT chk_holds_currency_format CHECK (currency ~ '^[A-Z]{3}$' AND payer_currency ~ '^[A-Z]{3}$'),
    -- Same currency: no rate and equal amounts. Different currencies: the rate the capture will use is locked here.
    CONSTRAINT chk_holds_fx CHECK (
        (currency = payer_currency AND fx_rate IS NULL AND fx_mid_rate IS NULL AND fx_spread IS NULL
            AND amount = payer_amount)
        OR (currency <> payer_currency AND fx_rate > 0 AND fx_mid_rate > 0 AND fx_spread >= 0)
    ),
    -- A capture never exceeds its hold, happens at most once, and is the only way to CAPTURED.
    CONSTRAINT chk_holds_capture CHECK (
        (status = 'CAPTURED'
            AND captured_amount > 0 AND captured_amount <= amount
            AND captured_payer_amount > 0 AND captured_payer_amount <= payer_amount
            AND capture_journal_entry_id IS NOT NULL)
        OR (status <> 'CAPTURED'
            AND captured_amount IS NULL AND captured_payer_amount IS NULL AND capture_journal_entry_id IS NULL)
    ),
    CONSTRAINT chk_holds_closed CHECK ((status = 'ACTIVE') = (closed_at IS NULL))
);

CREATE INDEX idx_holds_payer_active ON holds (payer_account_id) WHERE status = 'ACTIVE';
-- The expiry sweeper reads only active holds, oldest expiry first.
CREATE INDEX idx_holds_active_expiry ON holds (expires_at) WHERE status = 'ACTIVE';

ALTER TABLE journal_entries DROP CONSTRAINT chk_journal_entries_type;
ALTER TABLE journal_entries ADD CONSTRAINT chk_journal_entries_type
    CHECK (entry_type IN ('DEPOSIT', 'TRANSFER', 'FX_TRANSFER', 'REVERSAL', 'CAPTURE'));
