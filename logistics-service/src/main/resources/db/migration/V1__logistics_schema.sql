-- =============================================================================
-- FreightFlow Logistics Service — V1 Schema (ADRs 0006, 0008, 0009)
-- =============================================================================

CREATE SEQUENCE tracking_number_seq
    START WITH 1048576
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 32;

-- -----------------------------------------------------------------------------
-- 1. Corridor Locations (UN/LOCODE + IANA Time Zone)
-- -----------------------------------------------------------------------------
CREATE TABLE locations (
    unlocode      VARCHAR(5)    PRIMARY KEY,
    name          VARCHAR(120) NOT NULL,
    country_code  VARCHAR(2)   NOT NULL,
    iana_timezone VARCHAR(64)  NOT NULL,
    CONSTRAINT chk_locations_unlocode_format CHECK (unlocode ~ '^[A-Z]{2}[A-Z2-9]{3}$')
);

INSERT INTO locations (unlocode, name, country_code, iana_timezone) VALUES
    ('NAWVB', 'Walvis Bay',   'NA', 'Africa/Windhoek'),
    ('NAWDH', 'Windhoek',     'NA', 'Africa/Windhoek'),
    ('ZADUR', 'Durban',       'ZA', 'Africa/Johannesburg'),
    ('ZACPT', 'Cape Town',    'ZA', 'Africa/Johannesburg'),
    ('ZAJNB', 'Johannesburg', 'ZA', 'Africa/Johannesburg'),
    ('DEHAM', 'Hamburg',      'DE', 'Europe/Berlin'),
    ('DEBRE', 'Bremen',       'DE', 'Europe/Berlin'),
    ('DEFRA', 'Frankfurt',    'DE', 'Europe/Berlin');

-- -----------------------------------------------------------------------------
-- 2. Lane/Mode Tariffs (Seeded Reference Data)
-- -----------------------------------------------------------------------------
CREATE TABLE tariffs (
    id                           UUID           PRIMARY KEY,
    origin_unlocode              VARCHAR(5)     NOT NULL REFERENCES locations (unlocode),
    destination_unlocode         VARCHAR(5)     NOT NULL REFERENCES locations (unlocode),
    mode                         VARCHAR(16)    NOT NULL,
    billing_unit                 VARCHAR(16)    NOT NULL,
    currency                     VARCHAR(3)     NOT NULL,
    rate_per_unit                NUMERIC(19, 4) NOT NULL,
    minimum_charge               NUMERIC(19, 4) NOT NULL,
    fuel_surcharge_percent       NUMERIC(7, 4)  NOT NULL,
    origin_handling_fee          NUMERIC(19, 4) NOT NULL,
    destination_handling_fee     NUMERIC(19, 4) NOT NULL,
    import_duty_percent          NUMERIC(7, 4)  NOT NULL,
    transit_days_min             INTEGER        NOT NULL,
    transit_days_max             INTEGER        NOT NULL,
    CONSTRAINT uq_tariffs_lane_mode UNIQUE (origin_unlocode, destination_unlocode, mode),
    CONSTRAINT chk_tariffs_distinct_locations CHECK (origin_unlocode <> destination_unlocode),
    CONSTRAINT chk_tariffs_mode CHECK (mode IN ('SEA_LCL', 'AIR')),
    CONSTRAINT chk_tariffs_billing_unit CHECK (billing_unit IN ('WM_TON', 'KG')),
    CONSTRAINT chk_tariffs_currency CHECK (currency IN ('NAD', 'ZAR', 'EUR')),
    CONSTRAINT chk_tariffs_positive_rates CHECK (
        rate_per_unit > 0
        AND minimum_charge >= 0
        AND fuel_surcharge_percent >= 0
        AND origin_handling_fee >= 0
        AND destination_handling_fee >= 0
        AND import_duty_percent >= 0
        AND transit_days_min > 0
        AND transit_days_max >= transit_days_min
    )
);

-- Seed tariffs across the Walvis Bay / Windhoek / South Africa <-> Hamburg / Frankfurt / Bremen corridor
INSERT INTO tariffs (
    id, origin_unlocode, destination_unlocode, mode, billing_unit, currency,
    rate_per_unit, minimum_charge, fuel_surcharge_percent,
    origin_handling_fee, destination_handling_fee, import_duty_percent,
    transit_days_min, transit_days_max
) VALUES
    -- Walvis Bay (NAWVB) <-> Hamburg (DEHAM)
    ('01950000-0000-7000-8000-000000000101', 'NAWVB', 'DEHAM', 'SEA_LCL', 'WM_TON', 'EUR',  95.0000, 180.0000, 12.5000,  65.0000,  85.0000, 15.0000, 18, 24),
    ('01950000-0000-7000-8000-000000000102', 'NAWVB', 'DEHAM', 'AIR',     'KG',     'EUR',   4.8000, 220.0000, 18.0000,  75.0000,  95.0000, 15.0000,  2,  4),
    ('01950000-0000-7000-8000-000000000103', 'DEHAM', 'NAWVB', 'SEA_LCL', 'WM_TON', 'EUR', 105.0000, 195.0000, 12.5000,  85.0000,  65.0000, 16.5000, 18, 24),
    ('01950000-0000-7000-8000-000000000104', 'DEHAM', 'NAWVB', 'AIR',     'KG',     'EUR',   5.1000, 240.0000, 18.0000,  95.0000,  75.0000, 16.5000,  2,  4),
    -- Walvis Bay (NAWVB) <-> Bremen (DEBRE)
    ('01950000-0000-7000-8000-000000000105', 'NAWVB', 'DEBRE', 'SEA_LCL', 'WM_TON', 'EUR',  98.0000, 185.0000, 12.5000,  65.0000,  85.0000, 15.0000, 19, 25),
    ('01950000-0000-7000-8000-000000000106', 'DEBRE', 'NAWVB', 'SEA_LCL', 'WM_TON', 'EUR', 108.0000, 200.0000, 12.5000,  85.0000,  65.0000, 16.5000, 19, 25),
    -- Windhoek (NAWDH) <-> Frankfurt (DEFRA) & Hamburg (DEHAM)
    ('01950000-0000-7000-8000-000000000107', 'NAWDH', 'DEFRA', 'AIR',     'KG',     'EUR',   4.5000, 210.0000, 18.0000,  70.0000,  90.0000, 15.0000,  1,  3),
    ('01950000-0000-7000-8000-000000000108', 'DEFRA', 'NAWDH', 'AIR',     'KG',     'EUR',   4.7500, 225.0000, 18.0000,  90.0000,  70.0000, 16.5000,  1,  3),
    ('01950000-0000-7000-8000-000000000109', 'NAWDH', 'DEHAM', 'AIR',     'KG',     'EUR',   4.6500, 215.0000, 18.0000,  70.0000,  90.0000, 15.0000,  2,  4),
    -- Durban (ZADUR) / Cape Town (ZACPT) / Johannesburg (ZAJNB) <-> Hamburg (DEHAM) / Frankfurt (DEFRA)
    ('01950000-0000-7000-8000-000000000110', 'ZADUR', 'DEHAM', 'SEA_LCL', 'WM_TON', 'EUR',  92.0000, 175.0000, 12.5000,  60.0000,  85.0000, 15.0000, 20, 26),
    ('01950000-0000-7000-8000-000000000111', 'ZADUR', 'DEHAM', 'AIR',     'KG',     'EUR',   4.6000, 215.0000, 18.0000,  70.0000,  95.0000, 15.0000,  2,  4),
    ('01950000-0000-7000-8000-000000000112', 'ZACPT', 'DEHAM', 'SEA_LCL', 'WM_TON', 'EUR',  88.0000, 170.0000, 12.5000,  60.0000,  85.0000, 15.0000, 17, 22),
    ('01950000-0000-7000-8000-000000000113', 'ZAJNB', 'DEFRA', 'AIR',     'KG',     'EUR',   4.4000, 205.0000, 18.0000,  65.0000,  90.0000, 15.0000,  1,  3);

-- -----------------------------------------------------------------------------
-- 3. Freight Quotes & Packages (Snapshotted Tariff & Charge Breakdown)
-- -----------------------------------------------------------------------------
CREATE TABLE freight_quotes (
    id                          UUID           PRIMARY KEY,
    origin_unlocode             VARCHAR(5)     NOT NULL REFERENCES locations (unlocode),
    destination_unlocode        VARCHAR(5)     NOT NULL REFERENCES locations (unlocode),
    mode                        VARCHAR(16)    NOT NULL,
    incoterm                    VARCHAR(3)     NOT NULL,
    billing_unit                VARCHAR(16)    NOT NULL,
    package_count               INTEGER        NOT NULL,
    actual_weight_kg            NUMERIC(12, 3) NOT NULL,
    volume_cbm                  NUMERIC(12, 4) NOT NULL,
    volumetric_weight_kg        NUMERIC(12, 3) NOT NULL,
    chargeable_weight_kg        NUMERIC(12, 3) NOT NULL,
    chargeable_units            NUMERIC(12, 4) NOT NULL,
    tariff_currency             VARCHAR(3)     NOT NULL,
    snapshot_rate_per_unit      NUMERIC(19, 4) NOT NULL,
    snapshot_minimum_charge     NUMERIC(19, 4) NOT NULL,
    snapshot_fuel_surcharge_pct NUMERIC(7, 4)  NOT NULL,
    snapshot_fx_rate            NUMERIC(19, 8) NOT NULL,
    currency                    VARCHAR(3)     NOT NULL,
    base_freight_charge         NUMERIC(19, 4) NOT NULL,
    fuel_surcharge              NUMERIC(19, 4) NOT NULL,
    origin_handling_charge      NUMERIC(19, 4) NOT NULL,
    destination_handling_charge NUMERIC(19, 4) NOT NULL,
    import_customs_duty_charge  NUMERIC(19, 4) NOT NULL,
    total_amount                NUMERIC(19, 4) NOT NULL,
    transit_days_min            INTEGER        NOT NULL,
    transit_days_max            INTEGER        NOT NULL,
    valid_until                 TIMESTAMPTZ    NOT NULL,
    created_at                  TIMESTAMPTZ    NOT NULL,
    CONSTRAINT chk_quotes_mode CHECK (mode IN ('SEA_LCL', 'AIR')),
    CONSTRAINT chk_quotes_incoterm CHECK (incoterm IN ('EXW', 'FCA', 'DAP', 'DDP')),
    CONSTRAINT chk_quotes_currency CHECK (currency IN ('NAD', 'ZAR', 'EUR')),
    CONSTRAINT chk_quotes_positive_totals CHECK (
        package_count > 0
        AND actual_weight_kg > 0
        AND volume_cbm > 0
        AND chargeable_weight_kg > 0
        AND total_amount > 0
    )
);

CREATE TABLE quote_packages (
    id               UUID           PRIMARY KEY,
    quote_id         UUID           NOT NULL REFERENCES freight_quotes (id) ON DELETE CASCADE,
    line_number      INTEGER        NOT NULL,
    length_cm        NUMERIC(10, 2) NOT NULL,
    width_cm         NUMERIC(10, 2) NOT NULL,
    height_cm        NUMERIC(10, 2) NOT NULL,
    weight_kg        NUMERIC(10, 3) NOT NULL,
    quantity         INTEGER        NOT NULL,
    description      VARCHAR(200),
    CONSTRAINT uq_quote_packages_line UNIQUE (quote_id, line_number),
    CONSTRAINT chk_quote_packages_positive CHECK (
        length_cm > 0 AND width_cm > 0 AND height_cm > 0 AND weight_kg > 0 AND quantity > 0
    )
);

CREATE INDEX idx_quote_packages_quote_id ON quote_packages (quote_id, line_number);

-- -----------------------------------------------------------------------------
-- 4. Shipments & Out-of-Order Tracking Events (ADR 0009)
-- -----------------------------------------------------------------------------
CREATE TABLE shipments (
    id                      UUID           PRIMARY KEY,
    tracking_number         VARCHAR(13)    NOT NULL,
    quote_id                UUID           NOT NULL REFERENCES freight_quotes (id),
    origin_unlocode         VARCHAR(5)     NOT NULL REFERENCES locations (unlocode),
    destination_unlocode    VARCHAR(5)     NOT NULL REFERENCES locations (unlocode),
    mode                    VARCHAR(16)    NOT NULL,
    incoterm                VARCHAR(3)     NOT NULL,
    status                  VARCHAR(32)    NOT NULL,
    shipper_name            VARCHAR(160)   NOT NULL,
    shipper_address         VARCHAR(300)   NOT NULL,
    shipper_contact         VARCHAR(160)   NOT NULL,
    consignee_name          VARCHAR(160)   NOT NULL,
    consignee_address       VARCHAR(300)   NOT NULL,
    consignee_contact       VARCHAR(160)   NOT NULL,
    payer_account_number    VARCHAR(16)    NOT NULL,
    goods_description       VARCHAR(400)   NOT NULL,
    declared_value          NUMERIC(19, 4) NOT NULL,
    declared_value_currency VARCHAR(3)     NOT NULL,
    quoted_total_amount     NUMERIC(19, 4) NOT NULL,
    quoted_currency         VARCHAR(3)     NOT NULL,
    actual_weight_kg        NUMERIC(12, 3) NOT NULL,
    volume_cbm              NUMERIC(12, 4) NOT NULL,
    chargeable_weight_kg    NUMERIC(12, 3) NOT NULL,
    cancellation_reason     VARCHAR(300),
    latest_event_at         TIMESTAMPTZ,
    version                 BIGINT         NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ    NOT NULL,
    updated_at              TIMESTAMPTZ    NOT NULL,
    CONSTRAINT uq_shipments_tracking_number UNIQUE (tracking_number),
    CONSTRAINT chk_shipments_tracking_format CHECK (
        tracking_number ~ '^FF-[0-9A-HJ-NP-TV-Z]{8}-[0-9A-HJ-NP-TV-Z]$'
    ),
    CONSTRAINT chk_shipments_status CHECK (
        status IN (
            'AWAITING_PAYMENT',
            'BOOKED',
            'PICKED_UP',
            'AT_ORIGIN_PORT',
            'IN_TRANSIT',
            'AT_DESTINATION_PORT',
            'CUSTOMS_HOLD',
            'CUSTOMS_CLEARED',
            'OUT_FOR_DELIVERY',
            'DELIVERED',
            'CANCELLED',
            'EXCEPTION'
        )
    ),
    CONSTRAINT chk_shipments_declared_value CHECK (declared_value > 0)
);

-- Keyset pagination indexes for GET /api/v1/shipments?status=&cursor=&size=
CREATE INDEX idx_shipments_created_id_desc
    ON shipments (created_at DESC, id DESC);

CREATE INDEX idx_shipments_status_created_id_desc
    ON shipments (status, created_at DESC, id DESC);

CREATE TABLE tracking_events (
    id                UUID         PRIMARY KEY,
    shipment_id       UUID         NOT NULL REFERENCES shipments (id) ON DELETE CASCADE,
    carrier_event_id  VARCHAR(128),
    event_type        VARCHAR(32)  NOT NULL,
    target_status     VARCHAR(32)  NOT NULL,
    location_unlocode VARCHAR(5)   NOT NULL REFERENCES locations (unlocode),
    description       VARCHAR(300) NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL,
    recorded_at       TIMESTAMPTZ  NOT NULL,
    applied_to_status BOOLEAN      NOT NULL,
    CONSTRAINT chk_tracking_events_status CHECK (
        target_status IN (
            'AWAITING_PAYMENT',
            'BOOKED',
            'PICKED_UP',
            'AT_ORIGIN_PORT',
            'IN_TRANSIT',
            'AT_DESTINATION_PORT',
            'CUSTOMS_HOLD',
            'CUSTOMS_CLEARED',
            'OUT_FOR_DELIVERY',
            'DELIVERED',
            'CANCELLED',
            'EXCEPTION'
        )
    )
);

CREATE UNIQUE INDEX uq_tracking_events_carrier_event
    ON tracking_events (shipment_id, carrier_event_id)
    WHERE carrier_event_id IS NOT NULL;

CREATE INDEX idx_tracking_events_shipment_occurred
    ON tracking_events (shipment_id, occurred_at ASC, id ASC);

-- -----------------------------------------------------------------------------
-- 5. Transactional Idempotency Keys (ADR 0006)
-- -----------------------------------------------------------------------------
CREATE TABLE idempotency_keys (
    scope           VARCHAR(64)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    CHAR(64)     NOT NULL,
    status          VARCHAR(16)  NOT NULL,
    http_status     INTEGER,
    response_body   TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (scope, idempotency_key),
    CONSTRAINT chk_logistics_idempotency_status CHECK (status IN ('IN_PROGRESS', 'COMPLETED'))
);

CREATE INDEX idx_logistics_idempotency_expires_at
    ON idempotency_keys (expires_at);
