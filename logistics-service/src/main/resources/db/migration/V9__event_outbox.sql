-- Transactional outbox for domain events (ADR 0025).
--
-- A change and the event that reports it commit together: the service inserts the event row in the transaction
-- that changes the shipment. The relay publishes rows to Kafka and marks them published only
-- after the broker acknowledged them; published rows are pruned after a retention period.

-- The last sequence number handed out per aggregate. The upsert that takes the next one locks this row until the
-- transaction ends, so two transactions writing events for one aggregate commit their sequences in order. Rows are
-- never pruned: a sequence must not restart after the aggregate's old events are gone.
CREATE TABLE event_sequences (
    aggregate_id  UUID PRIMARY KEY,
    last_sequence BIGINT NOT NULL,
    CONSTRAINT chk_event_sequences_positive CHECK (last_sequence > 0)
);

CREATE TABLE event_outbox (
    -- The envelope's eventId (UUIDv7).
    id              UUID PRIMARY KEY,
    topic           VARCHAR(100) NOT NULL,
    event_type      VARCHAR(64)  NOT NULL,
    aggregate_id    UUID         NOT NULL,
    sequence        BIGINT       NOT NULL,
    -- The whole envelope as published, byte for byte (json keeps the text; jsonb would reorder it).
    envelope        JSON         NOT NULL,
    occurred_at     TIMESTAMPTZ  NOT NULL,
    attempts        INTEGER      NOT NULL DEFAULT 0,
    -- Earliest time a relay may pick the row up: the retry backoff, and the lease while a relay sends it.
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    last_error      VARCHAR(500),
    published_at    TIMESTAMPTZ,
    CONSTRAINT uq_event_outbox_sequence UNIQUE (aggregate_id, sequence),
    CONSTRAINT chk_event_outbox_sequence CHECK (sequence > 0),
    CONSTRAINT chk_event_outbox_attempts CHECK (attempts >= 0)
);

-- The relay's queue, and the "is an earlier event of this aggregate still unpublished?" lookup.
CREATE INDEX idx_event_outbox_due ON event_outbox (next_attempt_at) WHERE published_at IS NULL;
CREATE INDEX idx_event_outbox_unpublished ON event_outbox (aggregate_id, sequence) WHERE published_at IS NULL;
-- Pruning.
CREATE INDEX idx_event_outbox_published ON event_outbox (published_at) WHERE published_at IS NOT NULL;
