-- An operator may send an UNKNOWN email again (ADR 0027): it may have arrived already, so the operator acknowledges
-- that the customer may get it twice, and who did it is recorded (ADR 0020). The last resend is kept on the row; each
-- one is also logged.
ALTER TABLE notifications
    ADD COLUMN resend_count           INTEGER      NOT NULL DEFAULT 0,
    ADD COLUMN resent_at              TIMESTAMPTZ,
    ADD COLUMN resent_by_subject      VARCHAR(64),
    ADD COLUMN resent_by_client_id    VARCHAR(64),
    ADD COLUMN resend_request_id      VARCHAR(128),
    ADD CONSTRAINT chk_notifications_resend_count CHECK (resend_count >= 0),
    ADD CONSTRAINT chk_notifications_resent CHECK (
        (resend_count = 0) = (resent_at IS NULL) AND (resent_at IS NULL) = (resent_by_subject IS NULL));

CREATE INDEX idx_notifications_status ON notifications (status, created_at DESC) WHERE status IN ('UNKNOWN', 'FAILED');
