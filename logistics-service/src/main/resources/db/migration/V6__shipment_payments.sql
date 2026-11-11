-- Paying for a shipment (ADR 0012): a transactional outbox of payment commands.
--
-- Booking a shipment writes an AUTHORISE command in the same transaction. A relay sends pending commands to
-- banking-service with a deterministic Idempotency-Key, so a command whose response was lost is replayed by
-- banking, never executed twice. Capture (at pickup) and release (on cancellation) are commands in the same table.

CREATE TABLE shipment_payments (
    id                   UUID PRIMARY KEY,
    shipment_id          UUID           NOT NULL REFERENCES shipments (id),
    command              VARCHAR(16)    NOT NULL,
    status               VARCHAR(16)    NOT NULL,
    amount               NUMERIC(19, 4) NOT NULL,
    currency             VARCHAR(3)     NOT NULL,
    payer_account_number VARCHAR(16)    NOT NULL,
    idempotency_key      VARCHAR(128)   NOT NULL,
    hold_id              UUID,
    attempts             INTEGER        NOT NULL DEFAULT 0,
    -- Earliest time the relay may pick the command up: the retry backoff, and the lease while a relay works on it.
    next_attempt_at      TIMESTAMPTZ    NOT NULL,
    last_error           VARCHAR(500),
    problem_type         VARCHAR(200),
    problem_detail       VARCHAR(1000),
    created_at           TIMESTAMPTZ    NOT NULL,
    updated_at           TIMESTAMPTZ    NOT NULL,
    completed_at         TIMESTAMPTZ,
    CONSTRAINT uq_shipment_payments_command UNIQUE (shipment_id, command),
    CONSTRAINT uq_shipment_payments_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT chk_shipment_payments_command CHECK (command IN ('AUTHORISE', 'CAPTURE', 'RELEASE')),
    CONSTRAINT chk_shipment_payments_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED', 'PARKED')),
    CONSTRAINT chk_shipment_payments_amount CHECK (amount > 0),
    CONSTRAINT chk_shipment_payments_attempts CHECK (attempts >= 0),
    -- Capture and release act on a hold, so they always know which one.
    CONSTRAINT chk_shipment_payments_hold CHECK (command = 'AUTHORISE' OR hold_id IS NOT NULL),
    CONSTRAINT chk_shipment_payments_completed CHECK ((status IN ('SUCCEEDED', 'FAILED')) = (completed_at IS NOT NULL))
);

-- The relay's queue: pending commands, earliest first. PARKED commands wait for an operator.
CREATE INDEX idx_shipment_payments_due ON shipment_payments (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX idx_shipment_payments_parked ON shipment_payments (updated_at) WHERE status = 'PARKED';

ALTER TABLE shipments
    ADD COLUMN payment_status         VARCHAR(24),
    ADD COLUMN hold_id                UUID,
    ADD COLUMN payment_failure_type   VARCHAR(200),
    ADD COLUMN payment_failure_detail VARCHAR(1000);

-- Shipments booked before V6 still await payment: give them the AUTHORISE command they would have had.
UPDATE shipments SET payment_status = CASE WHEN status = 'AWAITING_PAYMENT' THEN 'AUTHORISATION_PENDING' ELSE 'NOT_REQUIRED' END;
ALTER TABLE shipments ALTER COLUMN payment_status SET NOT NULL;
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_payment_status CHECK (payment_status IN (
    'NOT_REQUIRED', 'AUTHORISATION_PENDING', 'AUTHORISED', 'CAPTURE_PENDING', 'CAPTURED',
    'RELEASE_PENDING', 'RELEASED', 'FAILED'));

INSERT INTO shipment_payments (id, shipment_id, command, status, amount, currency, payer_account_number,
                               idempotency_key, next_attempt_at, created_at, updated_at)
SELECT gen_random_uuid(), s.id, 'AUTHORISE', 'PENDING', s.quoted_total_amount, s.quoted_currency,
       s.payer_account_number, 'shipment-' || s.id || '-authorise', now(), now(), now()
FROM shipments s
WHERE s.status = 'AWAITING_PAYMENT';

ALTER TABLE shipments DROP CONSTRAINT chk_shipments_status;
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_status CHECK (status IN (
    'AWAITING_PAYMENT', 'PAYMENT_FAILED', 'BOOKED', 'PICKED_UP', 'AT_ORIGIN_PORT', 'IN_TRANSIT',
    'AT_DESTINATION_PORT', 'CUSTOMS_HOLD', 'CUSTOMS_CLEARED', 'OUT_FOR_DELIVERY', 'DELIVERED',
    'CANCELLED', 'EXCEPTION'));
ALTER TABLE tracking_events DROP CONSTRAINT chk_tracking_events_status;
ALTER TABLE tracking_events ADD CONSTRAINT chk_tracking_events_status CHECK (target_status IN (
    'AWAITING_PAYMENT', 'PAYMENT_FAILED', 'BOOKED', 'PICKED_UP', 'AT_ORIGIN_PORT', 'IN_TRANSIT',
    'AT_DESTINATION_PORT', 'CUSTOMS_HOLD', 'CUSTOMS_CLEARED', 'OUT_FOR_DELIVERY', 'DELIVERED',
    'CANCELLED', 'EXCEPTION'));
