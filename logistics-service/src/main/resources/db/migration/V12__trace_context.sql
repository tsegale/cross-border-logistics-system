-- The W3C traceparent of the transaction that wrote the row (ADR 0030): the event relay publishes an event in that
-- trace, and the payment relay calls banking in it, so one booking is one trace end to end.
ALTER TABLE event_outbox
    ADD COLUMN traceparent VARCHAR(55),
    ADD CONSTRAINT chk_event_outbox_traceparent
        CHECK (traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
ALTER TABLE shipment_payments
    ADD COLUMN traceparent VARCHAR(55),
    ADD CONSTRAINT chk_shipment_payments_traceparent
        CHECK (traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
