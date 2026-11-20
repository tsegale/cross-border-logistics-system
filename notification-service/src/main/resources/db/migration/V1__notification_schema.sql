-- notification-service's own database (ADR 0001, ADR 0027).

-- Who an organisation's emails go to, in which language, about what. Recipients are the only personal data this
-- service holds; they come from the organisation, never from an event (ADR 0024), and go with the row.
CREATE TABLE notification_settings (
    organization_id      UUID PRIMARY KEY,
    language             VARCHAR(2)   NOT NULL,
    recipients           TEXT[]       NOT NULL,
    kinds                TEXT[]       NOT NULL,
    version              BIGINT       NOT NULL,
    updated_at           TIMESTAMPTZ  NOT NULL,
    updated_by_subject   VARCHAR(64),
    updated_by_client_id VARCHAR(64)  NOT NULL,
    update_request_id    VARCHAR(128),
    CONSTRAINT chk_notification_settings_language CHECK (language IN ('de', 'en')),
    CONSTRAINT chk_notification_settings_recipients CHECK (cardinality(recipients) <= 10),
    CONSTRAINT chk_notification_settings_version CHECK (version > 0)
);

-- What this service knows of each shipment, from logistics' events: enough to name it in an email (the tracking
-- number) and to tell a stale status event from a new one (the last sequence applied).
CREATE TABLE shipments (
    shipment_id           UUID PRIMARY KEY,
    tracking_number       VARCHAR(16) NOT NULL,
    organization_id       UUID        NOT NULL,
    payer_organization_id UUID        NOT NULL,
    status                VARCHAR(32) NOT NULL,
    last_sequence         BIGINT      NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL
);

-- Consumed events by id, written in the transaction that acts on them (ADR 0026).
CREATE TABLE processed_events (
    event_id     UUID PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    event_type   VARCHAR(64)  NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL
);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);

-- One email to one recipient about one event. The unique key is what makes an event email a recipient at most once,
-- however often the event is delivered. The body is rendered when the row is written, so sending is transport only.
CREATE TABLE notifications (
    id              UUID PRIMARY KEY,
    event_id        UUID          NOT NULL,
    event_type      VARCHAR(64)   NOT NULL,
    kind            VARCHAR(32)   NOT NULL,
    organization_id UUID          NOT NULL,
    shipment_id     UUID,
    recipient       VARCHAR(320)  NOT NULL,
    language        VARCHAR(2)    NOT NULL,
    subject         VARCHAR(200)  NOT NULL,
    body_text       TEXT          NOT NULL,
    body_html       TEXT          NOT NULL,
    status          VARCHAR(16)   NOT NULL,
    attempts        INTEGER       NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ   NOT NULL,
    last_error      VARCHAR(500),
    created_at      TIMESTAMPTZ   NOT NULL,
    sent_at         TIMESTAMPTZ,
    CONSTRAINT uq_notifications_event_recipient UNIQUE (event_id, recipient),
    -- PENDING: to send; SENDING: handed to SMTP; SENT; FAILED: out of attempts; UNKNOWN: a sender died while
    -- SENDING, so it may or may not have gone out, and it is never sent again (at most once, ADR 0027).
    CONSTRAINT chk_notifications_status CHECK (status IN ('PENDING', 'SENDING', 'SENT', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_notifications_language CHECK (language IN ('de', 'en')),
    CONSTRAINT chk_notifications_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL)),
    CONSTRAINT chk_notifications_attempts CHECK (attempts >= 0)
);
CREATE INDEX idx_notifications_due ON notifications (next_attempt_at) WHERE status IN ('PENDING', 'SENDING');
CREATE INDEX idx_notifications_organization ON notifications (organization_id, created_at DESC);

-- Records that failed every retry (or were poison), from the dead-letter topics, until an operator redrives them.
CREATE TABLE dead_letters (
    id                    UUID PRIMARY KEY,
    topic                 VARCHAR(200)  NOT NULL,
    record_partition      INTEGER       NOT NULL,
    record_offset         BIGINT        NOT NULL,
    original_topic        VARCHAR(100)  NOT NULL,
    record_key            VARCHAR(200),
    record_value          TEXT          NOT NULL,
    event_id              UUID,
    event_type            VARCHAR(64),
    error                 VARCHAR(1000) NOT NULL,
    received_at           TIMESTAMPTZ   NOT NULL,
    status                VARCHAR(16)   NOT NULL,
    redrive_attempts      INTEGER       NOT NULL DEFAULT 0,
    last_redrive_error    VARCHAR(1000),
    redriven_at           TIMESTAMPTZ,
    redriven_by_subject   VARCHAR(64),
    redriven_by_client_id VARCHAR(64),
    redrive_request_id    VARCHAR(128),
    CONSTRAINT uq_dead_letters_record UNIQUE (topic, record_partition, record_offset),
    CONSTRAINT chk_dead_letters_status CHECK (status IN ('NEW', 'REDRIVEN')),
    CONSTRAINT chk_dead_letters_redriven CHECK ((status = 'REDRIVEN') = (redriven_at IS NOT NULL))
);
CREATE INDEX idx_dead_letters_status ON dead_letters (status, received_at);
