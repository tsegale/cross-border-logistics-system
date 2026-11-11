-- Holds that last until pickup (ADR 0011 revised, ADR 0012).
--
-- A shipment now has a planned pickup date. The hold is asked to expire a margin after it, capped by how far ahead
-- banking accepts an expiry; while the shipment waits for pickup, a job extends the hold before it runs out. Each
-- extension is an EXTEND payment command, so it goes through the same outbox, keys and retries as the others.

-- NULL for shipments booked before V7: their holds keep banking's default expiry and are not extended.
ALTER TABLE shipments
    ADD COLUMN planned_pickup_date DATE,
    ADD COLUMN hold_expires_at     TIMESTAMPTZ;

-- The expiry an AUTHORISE or EXTEND command asks banking for.
ALTER TABLE shipment_payments ADD COLUMN expires_at TIMESTAMPTZ;

-- A shipment still has at most one AUTHORISE, CAPTURE and RELEASE; it may need several extensions over time, but
-- never two open at once (a parked one waits for an operator before another is made).
ALTER TABLE shipment_payments DROP CONSTRAINT uq_shipment_payments_command;
CREATE UNIQUE INDEX uq_shipment_payments_command
    ON shipment_payments (shipment_id, command) WHERE command <> 'EXTEND';
CREATE UNIQUE INDEX uq_shipment_payments_open_extension
    ON shipment_payments (shipment_id) WHERE command = 'EXTEND' AND status IN ('PENDING', 'PARKED');

ALTER TABLE shipment_payments DROP CONSTRAINT chk_shipment_payments_command;
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_command
    CHECK (command IN ('AUTHORISE', 'CAPTURE', 'RELEASE', 'EXTEND'));
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_extension
    CHECK (command <> 'EXTEND' OR expires_at IS NOT NULL);

-- The renewal job reads booked shipments whose authorised hold runs out soonest.
CREATE INDEX idx_shipments_hold_expiry ON shipments (hold_expires_at)
    WHERE status = 'BOOKED' AND payment_status = 'AUTHORISED';
