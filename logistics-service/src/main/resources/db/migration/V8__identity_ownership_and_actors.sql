-- Identity (ADRs 0016-0020): quotes and shipments owned by customer organisations, carriers assigned to shipments,
-- idempotency keys scoped to the caller, and every booking, cancellation, tracking event and parked-command retry
-- recording who made it. Rows from before V8 belong to the "unassigned" organisation, as in banking's V7, which no
-- customer token names: only operators and auditors see them.

ALTER TABLE freight_quotes ADD COLUMN owner_organization_id UUID; -- NULL: an operator's quote

ALTER TABLE shipments
    ADD COLUMN booking_organization_id UUID,
    ADD COLUMN payer_organization_id   UUID,
    -- The carrier integration's OAuth client id; only it may report tracking events for the shipment.
    ADD COLUMN carrier_id              VARCHAR(64),
    ADD COLUMN booked_by_subject       VARCHAR(64),
    ADD COLUMN booked_by_client_id     VARCHAR(64),
    ADD COLUMN booking_request_id      VARCHAR(64),
    ADD COLUMN cancelled_by_subject    VARCHAR(64),
    ADD COLUMN cancelled_by_client_id  VARCHAR(64),
    ADD COLUMN cancellation_request_id VARCHAR(64);
UPDATE shipments SET booking_organization_id = '00000000-0000-0000-0000-000000000000',
                     payer_organization_id   = '00000000-0000-0000-0000-000000000000';
ALTER TABLE shipments ALTER COLUMN booking_organization_id SET NOT NULL;
ALTER TABLE shipments ALTER COLUMN payer_organization_id SET NOT NULL;
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_carrier_id CHECK (carrier_id IS NULL OR carrier_id ~ '^[a-z0-9-]{3,64}$');
CREATE INDEX idx_shipments_booking_org ON shipments (booking_organization_id, created_at DESC, id DESC);
CREATE INDEX idx_shipments_payer_org ON shipments (payer_organization_id, created_at DESC, id DESC);
CREATE INDEX idx_shipments_carrier ON shipments (carrier_id, created_at DESC, id DESC) WHERE carrier_id IS NOT NULL;

ALTER TABLE tracking_events
    ADD COLUMN recorded_by_subject   VARCHAR(64),
    ADD COLUMN recorded_by_client_id VARCHAR(64),
    ADD COLUMN request_id            VARCHAR(64);

-- banking checks the payer account belongs to this organisation (ADR 0018); the hold records who booked (ADR 0020).
ALTER TABLE shipment_payments
    ADD COLUMN payer_organization_id UUID,
    ADD COLUMN on_behalf_of_subject  VARCHAR(64),
    ADD COLUMN retried_by_subject    VARCHAR(64),
    ADD COLUMN retried_by_client_id  VARCHAR(64),
    ADD COLUMN retried_at            TIMESTAMPTZ;
UPDATE shipment_payments p SET payer_organization_id = s.payer_organization_id FROM shipments s WHERE s.id = p.shipment_id;
ALTER TABLE shipment_payments ALTER COLUMN payer_organization_id SET NOT NULL;

-- New bookings and tracking events always record who made them.
CREATE OR REPLACE FUNCTION require_booking_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.booked_by_client_id IS NULL OR NEW.booked_by_subject IS NULL THEN
        RAISE EXCEPTION 'shipment % has no booking actor', NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_shipments_require_actor BEFORE INSERT ON shipments
    FOR EACH ROW EXECUTE FUNCTION require_booking_actor();

CREATE OR REPLACE FUNCTION require_tracking_actor()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
BEGIN
    IF NEW.recorded_by_client_id IS NULL OR NEW.recorded_by_subject IS NULL THEN
        RAISE EXCEPTION 'tracking event % has no actor', NEW.id USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_tracking_events_require_actor BEFORE INSERT ON tracking_events
    FOR EACH ROW EXECUTE FUNCTION require_tracking_actor();

-- Idempotency keys scoped to the caller (ADR 0020).
ALTER TABLE idempotency_keys ADD COLUMN caller VARCHAR(200) NOT NULL DEFAULT 'legacy';
ALTER TABLE idempotency_keys ALTER COLUMN caller DROP DEFAULT;
ALTER TABLE idempotency_keys DROP CONSTRAINT idempotency_keys_pkey;
ALTER TABLE idempotency_keys ADD PRIMARY KEY (scope, caller, idempotency_key);
