-- Duties and import VAT into Namibia are owed to NamRA in NAD while the corridor's freight is priced in EUR
-- (ADR 0038). When FreightFlow collects them, they are held in their own hold, in NAD, on the payer's account: banking
-- locks the rate if that account is in another currency (ADR 0011), and each hold stays in one currency.

-- The shipment's duty hold, when it has one. duty_hold_status NULL: no separate duty hold (the duties are in the
-- freight hold, in its currency, or are not collected). The statuses are those of the freight hold (V6).
ALTER TABLE shipments ADD COLUMN duty_hold_status     VARCHAR(24);
ALTER TABLE shipments ADD COLUMN duty_hold_id         UUID;
ALTER TABLE shipments ADD COLUMN duty_hold_amount     NUMERIC(19, 4);
ALTER TABLE shipments ADD COLUMN duty_hold_currency   VARCHAR(3);
ALTER TABLE shipments ADD COLUMN duty_hold_expires_at TIMESTAMPTZ;
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_duty_hold_status CHECK (duty_hold_status IS NULL OR duty_hold_status IN (
    'AUTHORISATION_PENDING', 'AUTHORISED', 'CAPTURE_PENDING', 'CAPTURED', 'RELEASE_PENDING', 'RELEASED', 'FAILED'));
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_duty_hold CHECK ((duty_hold_status IS NULL)
    = (duty_hold_amount IS NULL) AND (duty_hold_amount IS NULL) = (duty_hold_currency IS NULL));
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_duty_hold_amount CHECK (duty_hold_amount IS NULL OR duty_hold_amount > 0);
-- A shipment's duties are in the freight hold or in their own, never both.
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_duties_held_once
    CHECK (duty_hold_status IS NULL OR customs_disbursement_amount = 0);

-- Which hold a payment command is for. Every command so far was for the freight hold.
ALTER TABLE shipment_payments ADD COLUMN hold_kind VARCHAR(8) NOT NULL DEFAULT 'FREIGHT';
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_hold_kind CHECK (hold_kind IN ('FREIGHT', 'DUTIES'));
-- A duty hold's capture is all disbursement; the assessment settles against it.
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_duty_capture
    CHECK (hold_kind = 'FREIGHT' OR command <> 'CAPTURE' OR disbursement_amount = amount);

-- One command of each kind per hold, one open extension and one open re-authorisation per hold (V7, V10).
DROP INDEX uq_shipment_payments_command;
CREATE UNIQUE INDEX uq_shipment_payments_command
    ON shipment_payments (shipment_id, hold_kind, command) WHERE command NOT IN ('EXTEND', 'REAUTHORISE');
DROP INDEX uq_shipment_payments_open_extension;
CREATE UNIQUE INDEX uq_shipment_payments_open_extension
    ON shipment_payments (shipment_id, hold_kind) WHERE command = 'EXTEND' AND status IN ('PENDING', 'PARKED');
DROP INDEX uq_shipment_payments_open_reauthorisation;
CREATE UNIQUE INDEX uq_shipment_payments_open_reauthorisation
    ON shipment_payments (shipment_id, hold_kind) WHERE command = 'REAUTHORISE' AND status IN ('PENDING', 'PARKED');
