-- DCSA transport events reach shipments (ADR 0037, Phase 7). A transport event is about a vessel's call at a port,
-- not a container, so it names no equipmentReference. DCSA T&T 2.2 links it to bookings in two ways: its
-- documentReferences (BKG: the carrier's booking reference) and its transportCall (vessel IMO number and voyage),
-- which the equipment events of the same voyage carry too.

-- The carrier's reference for the booking that carries this shipment: DCSA's carrierBookingReference (max 35).
ALTER TABLE shipments ADD COLUMN carrier_booking_reference VARCHAR(35);
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_carrier_booking_reference
    CHECK (carrier_booking_reference IS NULL OR carrier_booking_reference ~ '^[A-Za-z0-9/_.-]{1,35}$');
CREATE INDEX idx_shipments_carrier_booking ON shipments (carrier_id, carrier_booking_reference)
    WHERE carrier_booking_reference IS NOT NULL;

-- The vessel and voyage a shipment's container was seen on, from its equipment events (LOAD at the port of loading,
-- DISC at the port of discharge, any event with a vessel in its transportCall). A transport event of the same
-- carrier, vessel and voyage is about these shipments. One row per equipment event that named a vessel.
CREATE TABLE shipment_transport_calls (
    shipment_id         UUID          NOT NULL REFERENCES shipments (id),
    dcsa_event_id       UUID          NOT NULL REFERENCES dcsa_events (id),
    carrier_id          VARCHAR(64)   NOT NULL,
    vessel_imo_number   VARCHAR(7)    NOT NULL,
    export_voyage       VARCHAR(50),
    import_voyage       VARCHAR(50),
    un_location_code    VARCHAR(5),
    recorded_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (shipment_id, dcsa_event_id),
    CONSTRAINT chk_shipment_transport_calls_imo CHECK (vessel_imo_number ~ '^[0-9]{7}$'),
    CONSTRAINT chk_shipment_transport_calls_voyage CHECK (export_voyage IS NOT NULL OR import_voyage IS NOT NULL)
);
CREATE INDEX idx_shipment_transport_calls_vessel ON shipment_transport_calls (carrier_id, vessel_imo_number);

-- Which shipments one DCSA event reached. An equipment event reaches every open shipment in its container (LCL
-- consolidations share one), a transport event every shipment on its vessel's voyage; dcsa_events.shipment_id
-- names the first only, and stays for the events processed before this migration.
CREATE TABLE dcsa_event_shipments (
    dcsa_event_id       UUID          NOT NULL REFERENCES dcsa_events (id),
    shipment_id         UUID          NOT NULL REFERENCES shipments (id),
    -- APPLIED: recorded as a tracking event (which may still be a duplicate or out of order, ADR 0009).
    -- IGNORED: no milestone for this shipment (a transhipment port, or an event before or after its leg).
    outcome             VARCHAR(16)   NOT NULL,
    tracking_event_id   UUID REFERENCES tracking_events (id),
    matched_by          VARCHAR(20)   NOT NULL,
    recorded_at         TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (dcsa_event_id, shipment_id),
    CONSTRAINT chk_dcsa_event_shipments_outcome CHECK (outcome IN ('APPLIED', 'IGNORED')),
    CONSTRAINT chk_dcsa_event_shipments_applied CHECK ((outcome = 'APPLIED') = (tracking_event_id IS NOT NULL)),
    CONSTRAINT chk_dcsa_event_shipments_matched_by
        CHECK (matched_by IN ('EQUIPMENT', 'BOOKING_REFERENCE', 'TRANSPORT_CALL'))
);
CREATE INDEX idx_dcsa_event_shipments_shipment ON dcsa_event_shipments (shipment_id);
