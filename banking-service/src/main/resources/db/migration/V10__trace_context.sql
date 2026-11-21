-- The W3C traceparent of the transaction that wrote the event (ADR 0030): the relay publishes it in that trace.
ALTER TABLE event_outbox
    ADD COLUMN traceparent VARCHAR(55),
    ADD CONSTRAINT chk_event_outbox_traceparent
        CHECK (traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
