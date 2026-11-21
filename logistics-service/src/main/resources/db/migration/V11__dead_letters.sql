-- Dead letters of logistics' consumers (ADR 0026), kept for operators to list and redrive
-- (/api/v1/logistics/dead-letters). The record also goes to <topic>.logistics-service.dlt; this table is the
-- operator's view and records who redrove what (ADR 0020). One row per original record: a second recovery of the
-- same record changes nothing.
CREATE TABLE dead_letters (
    id                    UUID PRIMARY KEY,
    original_topic        VARCHAR(200)  NOT NULL,
    record_partition      INTEGER       NOT NULL,
    record_offset         BIGINT        NOT NULL,
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
    CONSTRAINT uq_dead_letters_record UNIQUE (original_topic, record_partition, record_offset),
    CONSTRAINT chk_dead_letters_status CHECK (status IN ('NEW', 'REDRIVEN')),
    CONSTRAINT chk_dead_letters_redriven CHECK ((status = 'REDRIVEN') = (redriven_at IS NOT NULL)),
    CONSTRAINT chk_dead_letters_redriven_by CHECK (status = 'NEW' OR redriven_by_subject IS NOT NULL)
);
CREATE INDEX idx_dead_letters_status ON dead_letters (status, received_at);
