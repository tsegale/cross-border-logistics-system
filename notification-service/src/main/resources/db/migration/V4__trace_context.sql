-- The W3C traceparent of the consumed event's span (ADR 0030): the email is sent in the trace of the booking (or
-- payment) that caused it.
ALTER TABLE notifications
    ADD COLUMN traceparent VARCHAR(55),
    ADD CONSTRAINT chk_notifications_traceparent
        CHECK (traceparent IS NULL OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$');
