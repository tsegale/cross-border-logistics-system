-- Duties and import VAT collected with the freight (ADR 0035, ADR 0036).

-- What the booking holds for duties and taxes on top of the freight (zero when they are not collected), in the
-- quote currency; the capture sends it to banking as the disbursement part.
ALTER TABLE shipments ADD COLUMN customs_disbursement_amount NUMERIC(19, 4) NOT NULL DEFAULT 0;
ALTER TABLE shipments ADD CONSTRAINT chk_shipments_disbursement CHECK (customs_disbursement_amount >= 0);
ALTER TABLE shipments ADD COLUMN customs_settled_at TIMESTAMPTZ;

-- A capture says how much of it is a disbursement; a customs settlement pays the assessed amount, which may be zero,
-- with the authority's reference.
ALTER TABLE shipment_payments ADD COLUMN disbursement_amount NUMERIC(19, 4);
ALTER TABLE shipment_payments ADD COLUMN authority_reference VARCHAR(64);
ALTER TABLE shipment_payments ALTER COLUMN command TYPE VARCHAR(32);
ALTER TABLE shipment_payments DROP CONSTRAINT chk_shipment_payments_command;
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_command
    CHECK (command IN ('AUTHORISE', 'CAPTURE', 'RELEASE', 'EXTEND', 'REAUTHORISE', 'CUSTOMS_SETTLEMENT'));
ALTER TABLE shipment_payments DROP CONSTRAINT chk_shipment_payments_amount;
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_amount
    CHECK (amount > 0 OR (command = 'CUSTOMS_SETTLEMENT' AND amount = 0));
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_disbursement
    CHECK (disbursement_amount IS NULL OR (command = 'CAPTURE' AND disbursement_amount >= 0
        AND disbursement_amount <= amount));
ALTER TABLE shipment_payments ADD CONSTRAINT chk_shipment_payments_authority
    CHECK ((command = 'CUSTOMS_SETTLEMENT') = (authority_reference IS NOT NULL));

-- The authority's assessment, four eyes (ADR 0021): one operator records what the notice says, a second confirms it,
-- and only the confirmation sends the settlement.
CREATE TABLE customs_assessments (
    id                     UUID PRIMARY KEY,
    shipment_id            UUID          NOT NULL UNIQUE REFERENCES shipments (id),
    status                 VARCHAR(16)   NOT NULL,
    currency               VARCHAR(3)    NOT NULL,
    duties                 NUMERIC(19, 4) NOT NULL,
    import_vat             NUMERIC(19, 4) NOT NULL,
    estimate               NUMERIC(19, 4) NOT NULL,
    authority_reference    VARCHAR(64)   NOT NULL,
    recorded_by_subject    VARCHAR(64)   NOT NULL,
    recorded_by_client_id  VARCHAR(64)   NOT NULL,
    recorded_at            TIMESTAMPTZ   NOT NULL,
    confirmed_by_subject   VARCHAR(64),
    confirmed_by_client_id VARCHAR(64),
    confirmed_at           TIMESTAMPTZ,
    CONSTRAINT chk_customs_assessments_status CHECK (status IN ('PENDING', 'CONFIRMED')),
    CONSTRAINT chk_customs_assessments_amounts CHECK (duties >= 0 AND import_vat >= 0 AND estimate >= 0),
    CONSTRAINT chk_customs_assessments_confirmed CHECK ((status = 'CONFIRMED') = (confirmed_at IS NOT NULL)
        AND (confirmed_at IS NULL) = (confirmed_by_subject IS NULL)),
    -- The point of it: whoever recorded the notice never confirms it.
    CONSTRAINT chk_customs_assessments_four_eyes CHECK (confirmed_by_subject IS NULL
        OR confirmed_by_subject <> recorded_by_subject)
);
