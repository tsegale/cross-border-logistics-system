-- logistics reacts to what banking did on its own (ADR 0028).
--
-- 1. A hold banking expired on a shipment still awaiting pickup is authorised again: a REAUTHORISE payment command,
--    sent like the others (ADR 0012). Its key names the expired hold, so one expiry causes at most one
--    re-authorisation, and at most one is open per shipment at a time.
-- 2. Consumed events are recorded by eventId in the transaction that acts on them, so a redelivered event changes
--    nothing (ADR 0026).

ALTER TABLE shipment_payments DROP CONSTRAINT chk_shipment_payments_command;
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_command
    CHECK (command IN ('AUTHORISE', 'CAPTURE', 'RELEASE', 'EXTEND', 'REAUTHORISE'));

DROP INDEX uq_shipment_payments_command;
CREATE UNIQUE INDEX uq_shipment_payments_command
    ON shipment_payments (shipment_id, command) WHERE command NOT IN ('EXTEND', 'REAUTHORISE');
CREATE UNIQUE INDEX uq_shipment_payments_open_reauthorisation
    ON shipment_payments (shipment_id) WHERE command = 'REAUTHORISE' AND status IN ('PENDING', 'PARKED');
-- A re-authorisation also asks banking for an expiry.
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_reauthorisation
    CHECK (command <> 'REAUTHORISE' OR expires_at IS NOT NULL);

CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL
);
-- Pruned once a redelivery can no longer happen: after the topics' retention (ADR 0022).
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
