-- Carrier events in the DCSA Track and Trace standard (ADR 0037).

-- The container a shipment travels in: an ISO 6346 number, the equipmentReference of DCSA's equipment events.
ALTER TABLE shipments ADD COLUMN container_number VARCHAR(11);
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_container_number
    CHECK (container_number IS NULL OR container_number ~ '^[A-Z]{3}[UJZ][0-9]{7}$');
CREATE INDEX idx_shipments_container ON shipments (container_number) WHERE container_number IS NOT NULL;

-- Every event a carrier sent, pushed or polled, as it was sent: the DCSA eventID deduplicates, the payload is the
-- audit record (id is the eventID). Stored at once and answered; turned into a tracking event afterwards, by the
-- processor.
CREATE TABLE dcsa_events (
    -- The DCSA eventID.
    id                  UUID PRIMARY KEY,
    carrier_id          VARCHAR(64)   NOT NULL,
    subscription_id     UUID,
    channel             VARCHAR(8)    NOT NULL,
    event_type          VARCHAR(16)   NOT NULL,
    event_created_at    TIMESTAMPTZ   NOT NULL,
    equipment_reference VARCHAR(11),
    payload             JSONB         NOT NULL,
    received_at         TIMESTAMPTZ   NOT NULL,
    status              VARCHAR(16)   NOT NULL,
    shipment_id         UUID REFERENCES shipments (id),
    outcome             VARCHAR(300),
    processed_at        TIMESTAMPTZ,
    next_attempt_at     TIMESTAMPTZ   NOT NULL,
    attempts            INT           NOT NULL DEFAULT 0,
    traceparent         VARCHAR(55),
    CONSTRAINT chk_dcsa_events_channel CHECK (channel IN ('PUSH', 'POLL')),
    CONSTRAINT chk_dcsa_events_type CHECK (event_type IN ('EQUIPMENT', 'TRANSPORT', 'SHIPMENT')),
    -- PENDING: not processed yet. APPLIED: became a tracking event. IGNORED: no milestone (planned, estimated or a
    -- code FreightFlow does not track). UNMATCHED: no shipment has the container. FAILED: out of attempts.
    CONSTRAINT chk_dcsa_events_status CHECK (status IN ('PENDING', 'APPLIED', 'IGNORED', 'UNMATCHED', 'FAILED')),
    CONSTRAINT chk_dcsa_events_processed CHECK ((status = 'PENDING') = (processed_at IS NULL)),
    CONSTRAINT chk_dcsa_events_traceparent CHECK (traceparent IS NULL
        OR traceparent ~ '^00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}$')
);
CREATE INDEX idx_dcsa_events_pending ON dcsa_events (next_attempt_at) WHERE status = 'PENDING';

-- Where polling got to, per carrier: the newest eventCreatedDateTime seen.
CREATE TABLE dcsa_poll_cursors (
    carrier_id       VARCHAR(64) PRIMARY KEY,
    created_after    TIMESTAMPTZ NOT NULL,
    last_success_at  TIMESTAMPTZ,
    last_failure_at  TIMESTAMPTZ,
    last_error       VARCHAR(300)
);
