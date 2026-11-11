-- Keeping a hold alive until pickup (ADR 0011, revised in Phase 3).
--
-- A sea shipment can be booked weeks before its cargo is picked up, longer than one hold may last. logistics extends
-- the hold while the shipment waits. An extension moves expires_at later and keeps the amount and the locked FX rate;
-- every extension is recorded here, append-only, so the history of a hold's expiry is never lost.

ALTER TABLE holds ADD CONSTRAINT chk_holds_expiry_after_creation CHECK (expires_at > created_at);

CREATE TABLE hold_extensions (
    id                  UUID PRIMARY KEY,
    hold_id             UUID        NOT NULL REFERENCES holds (id),
    previous_expires_at TIMESTAMPTZ NOT NULL,
    new_expires_at      TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL,
    -- An extension only ever moves the expiry later.
    CONSTRAINT chk_hold_extensions_later CHECK (new_expires_at > previous_expires_at)
);

CREATE INDEX idx_hold_extensions_hold ON hold_extensions (hold_id, created_at);

-- Append-only, like the ledger (ADR 0004), with the same trigger function.
CREATE TRIGGER trg_hold_extensions_append_only
    BEFORE UPDATE OR DELETE ON hold_extensions
    FOR EACH ROW
    EXECUTE FUNCTION reject_ledger_mutation();
