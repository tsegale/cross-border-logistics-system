-- Duties and import VAT (ADR 0035): a dated subset of each authoritative tariff, for the goods codes FreightFlow
-- quotes. Neither the EU's TARIC nor the SACU tariff has a public API (docs/tariffs/README.md). Updating one is a new
-- migration with a new source row: old rows stay, as the record of what earlier quotes used.

CREATE TABLE customs_tariff_sources (
    id             UUID PRIMARY KEY,
    -- EU: imports into the EU (Germany); SACU: imports into Namibia.
    market         VARCHAR(8)    NOT NULL,
    name           VARCHAR(200)  NOT NULL,
    url            VARCHAR(300)  NOT NULL,
    -- The tariff's own date, the day it was read, and the day it must be checked again by.
    reference_date DATE          NOT NULL,
    retrieved_on   DATE          NOT NULL,
    review_by      DATE          NOT NULL,
    evidence       VARCHAR(300)  NOT NULL,
    CONSTRAINT chk_tariff_sources_market CHECK (market IN ('EU', 'SACU')),
    CONSTRAINT chk_tariff_sources_dates CHECK (review_by > retrieved_on AND retrieved_on >= reference_date),
    CONSTRAINT uq_tariff_sources UNIQUE (market, reference_date)
);

CREATE TABLE customs_tariff_lines (
    source_id               UUID          NOT NULL REFERENCES customs_tariff_sources (id),
    -- 10 digits for TARIC, 8 for the SACU tariff.
    code                    VARCHAR(10)   NOT NULL,
    description             VARCHAR(300)  NOT NULL,
    -- The general (EU: third-country, erga omnes; SACU: General column) duty: ad valorem, plus a specific amount per
    -- 100 kg net where the tariff has one.
    duty_percent            NUMERIC(7, 4) NOT NULL,
    duty_per_100kg          NUMERIC(19, 4),
    duty_per_100kg_currency VARCHAR(3),
    duty_legal_base         VARCHAR(40)   NOT NULL,
    -- The preference and the origins it is for: NA under the EU-SADC EPA into the EU; EU under the same agreement
    -- into SACU. NULL: no preference loaded for this code.
    preference_scheme       VARCHAR(40),
    preference_origins      VARCHAR(100),
    preference_percent      NUMERIC(7, 4),
    preference_legal_base   VARCHAR(40),
    vat_percent             NUMERIC(5, 2) NOT NULL,
    vat_legal_base          VARCHAR(80)   NOT NULL,
    PRIMARY KEY (source_id, code),
    CONSTRAINT chk_tariff_lines_code CHECK (code ~ '^[0-9]{8}([0-9]{2})?$'),
    CONSTRAINT chk_tariff_lines_rates CHECK (duty_percent >= 0 AND duty_percent <= 100 AND vat_percent >= 0
        AND vat_percent <= 100 AND (preference_percent IS NULL OR preference_percent BETWEEN 0 AND 100)),
    CONSTRAINT chk_tariff_lines_specific CHECK ((duty_per_100kg IS NULL) = (duty_per_100kg_currency IS NULL)
        AND (duty_per_100kg IS NULL OR duty_per_100kg > 0)),
    CONSTRAINT chk_tariff_lines_preference CHECK ((preference_scheme IS NULL) = (preference_percent IS NULL)
        AND (preference_scheme IS NULL) = (preference_origins IS NULL)
        AND (preference_scheme IS NULL) = (preference_legal_base IS NULL))
);

-- EU: TARIC, reference date 2026-11-24, origin NA, read by scripts/fetch_taric.py
-- (docs/tariffs/taric-2026-11-24-origin-NA.json). The CN changes every 1 January: review by 2026-12-31.
-- Import VAT: § 12 UStG, 19 %, or 7 % for the goods of Anlage 2 (Nr. 2 meat, Nr. 3 fish, Nr. 11 fruit).
INSERT INTO customs_tariff_sources (id, market, name, url, reference_date, retrieved_on, review_by, evidence) VALUES
    ('01a20c00-0000-7000-8000-000000000001', 'EU', 'EU TARIC consultation (DG TAXUD), last TARIC update 2026-11-23',
     'https://ec.europa.eu/taxation_customs/dds2/taric/taric_consultation.jsp', '2026-11-24', '2026-11-24',
     '2026-12-31', 'docs/tariffs/taric-2026-11-24-origin-NA.json'),
    ('01a20c00-0000-7000-8000-000000000002', 'SACU',
     'SARS Schedule No. 1 Part 1 to the Customs and Excise Act, chapters 1 to 99 (SACU common external tariff)',
     'https://www.sars.gov.za/legal-lprim-ce-sch1p1chpt1-to-99-schedule-no-1-part-1-chapters-1-to-99/',
     '2026-11-13', '2026-11-24', '2027-02-13',
     'docs/tariffs/README.md (PDF SHA-256 68e9acf6131b73a90d3dd88c3a092fa5726aa93ecb1653629a850621c1f90795)');

INSERT INTO customs_tariff_lines (source_id, code, description, duty_percent, duty_per_100kg, duty_per_100kg_currency,
                                  duty_legal_base, preference_scheme, preference_origins, preference_percent,
                                  preference_legal_base, vat_percent, vat_legal_base) VALUES
    ('01a20c00-0000-7000-8000-000000000001', '0304741100',
     'Frozen fillets of Cape hake (Merluccius capensis) and deepwater hake (Merluccius paradoxus)',
     7.5000, NULL, NULL, 'R1006/11', 'EU-SADC EPA', 'NA', 0, 'D1623/16', 7.00, '§ 12 Abs. 2 Nr. 1 UStG, Anlage 2 Nr. 3'),
    ('01a20c00-0000-7000-8000-000000000001', '0804100091',
     'Dates, fresh or dried, in immediate containers of a net capacity not exceeding 35 kg',
     7.7000, NULL, NULL, 'R2204/99', 'EU-SADC EPA', 'NA', 0, 'D1623/16', 7.00, '§ 12 Abs. 2 Nr. 1 UStG, Anlage 2 Nr. 11'),
    ('01a20c00-0000-7000-8000-000000000001', '0804100099', 'Dates, fresh or dried, other',
     7.7000, NULL, NULL, 'R2204/99', 'EU-SADC EPA', 'NA', 0, 'D1623/16', 7.00, '§ 12 Abs. 2 Nr. 1 UStG, Anlage 2 Nr. 11'),
    ('01a20c00-0000-7000-8000-000000000001', '4402900090', 'Wood charcoal, whether or not agglomerated, other',
     0, NULL, NULL, 'R2658/87', 'EU-SADC EPA', 'NA', 0, 'D1623/16', 19.00, '§ 12 Abs. 1 UStG'),
    ('01a20c00-0000-7000-8000-000000000001', '0201300090', 'Meat of bovine animals, fresh or chilled, boneless, other',
     12.8000, 303.4000, 'EUR', 'R2204/99', 'EU-SADC EPA', 'NA', 0, 'D1623/16', 7.00,
     '§ 12 Abs. 2 Nr. 1 UStG, Anlage 2 Nr. 2'),
-- SACU: SARS Schedule 1 Part 1 dated 2026-11-13 (docs/tariffs/README.md). The EU / UK column is the EU-SADC EPA.
-- Namibian VAT: 15 %, section 6(1)(b) of the Value-Added Tax Act 10 of 2000.
    ('01a20c00-0000-7000-8000-000000000002', '84181020',
     'Combined refrigerator-freezers, fitted with separate external doors or drawers, total capacity not exceeding 400 l',
     25.0000, NULL, NULL, 'Schedule 1 Part 1', 'EU-SADC EPA', 'EU', 0, 'Schedule 1 Part 1, EU / UK column', 15.00,
     'VAT Act 10 of 2000, s. 6(1)(b)'),
    ('01a20c00-0000-7000-8000-000000000002', '84295120',
     'Front-end shovel loaders, not tracked, internal combustion, 3 000 kg to 30 000 kg (excluding for mines)',
     10.0000, NULL, NULL, 'Schedule 1 Part 1', 'EU-SADC EPA', 'EU', 0, 'Schedule 1 Part 1, EU / UK column', 15.00,
     'VAT Act 10 of 2000, s. 6(1)(b)'),
    ('01a20c00-0000-7000-8000-000000000002', '22042141',
     'Unfortified wine of fresh grapes, 4.5 % to 16.5 % vol, in containers holding 2 l or less',
     25.0000, NULL, NULL, 'Schedule 1 Part 1', 'EU-SADC EPA', 'EU', 0, 'Schedule 1 Part 1, EU / UK column', 15.00,
     'VAT Act 10 of 2000, s. 6(1)(b)'),
    ('01a20c00-0000-7000-8000-000000000002', '84713090', 'Portable automatic data processing machines, other',
     0, NULL, NULL, 'Schedule 1 Part 1', 'EU-SADC EPA', 'EU', 0, 'Schedule 1 Part 1, EU / UK column', 15.00,
     'VAT Act 10 of 2000, s. 6(1)(b)');

-- A quote's goods (ADR 0035): what the customer declares, and the estimate computed from the tariff source above.
CREATE TABLE quote_goods_lines (
    quote_id                  UUID          NOT NULL REFERENCES freight_quotes (id),
    line_number               INT           NOT NULL,
    hs_code                   VARCHAR(10)   NOT NULL,
    description               VARCHAR(200)  NOT NULL,
    origin_country            VARCHAR(2)    NOT NULL,
    quantity                  INT           NOT NULL,
    net_weight_kg             NUMERIC(12, 3) NOT NULL,
    declared_value            NUMERIC(19, 4) NOT NULL,
    declared_currency         VARCHAR(3)    NOT NULL,
    -- The proof of preferential origin as declared (EPA Protocol 1, Article 19): EUR1 or ORIGIN_DECLARATION.
    origin_proof_type         VARCHAR(24),
    origin_proof_reference    VARCHAR(64),
    origin_proof_issued_on    DATE,
    approved_exporter_number  VARCHAR(64),
    -- The estimate, in the customs currency (EUR into the EU, NAD into Namibia).
    rate_to_customs_currency  NUMERIC(19, 8) NOT NULL,
    customs_value             NUMERIC(19, 4) NOT NULL,
    preference_applied        BOOLEAN       NOT NULL,
    preference_note           VARCHAR(300)  NOT NULL,
    duty_rate_text            VARCHAR(80)   NOT NULL,
    duty                      NUMERIC(19, 4) NOT NULL,
    vat_base                  NUMERIC(19, 4) NOT NULL,
    vat_percent               NUMERIC(5, 2) NOT NULL,
    import_vat                NUMERIC(19, 4) NOT NULL,
    PRIMARY KEY (quote_id, line_number),
    CONSTRAINT chk_goods_hs_code CHECK (hs_code ~ '^[0-9]{8}([0-9]{2})?$'),
    CONSTRAINT chk_goods_origin CHECK (origin_country ~ '^[A-Z]{2}$' AND declared_currency ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_goods_amounts CHECK (quantity > 0 AND net_weight_kg > 0 AND declared_value > 0
        AND rate_to_customs_currency > 0 AND customs_value > 0 AND duty >= 0 AND vat_base > 0 AND import_vat >= 0),
    CONSTRAINT chk_goods_proof CHECK (origin_proof_type IS NULL OR (origin_proof_type IN ('EUR1', 'ORIGIN_DECLARATION')
        AND origin_proof_reference IS NOT NULL AND origin_proof_issued_on IS NOT NULL))
);

-- The quote's customs estimate: totals, who pays them (DDP: the shipper, otherwise the consignee, ADR 0008) and
-- whether FreightFlow collects them with the freight (the payer is the one quoting, in the freight currency).
ALTER TABLE freight_quotes
    ADD COLUMN customs_market          VARCHAR(8),
    ADD COLUMN customs_tariff_source_id UUID REFERENCES customs_tariff_sources (id),
    ADD COLUMN customs_currency        VARCHAR(3),
    ADD COLUMN estimated_duties        NUMERIC(19, 4),
    ADD COLUMN estimated_import_vat    NUMERIC(19, 4),
    ADD COLUMN duties_paid_by          VARCHAR(16),
    ADD COLUMN duties_collected        BOOLEAN,
    ADD CONSTRAINT chk_quotes_customs CHECK ((customs_market IS NULL) = (customs_tariff_source_id IS NULL)
        AND (customs_market IS NULL) = (estimated_duties IS NULL)
        AND (customs_market IS NULL) = (estimated_import_vat IS NULL)
        AND (customs_market IS NULL) = (customs_currency IS NULL)
        AND (customs_market IS NULL) = (duties_paid_by IS NULL)
        AND (customs_market IS NULL) = (duties_collected IS NULL)
        AND (estimated_duties IS NULL OR (estimated_duties >= 0 AND estimated_import_vat >= 0)));
