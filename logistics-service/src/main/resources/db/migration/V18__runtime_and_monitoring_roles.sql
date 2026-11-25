-- Least privilege in the database (ADR 0041). The owner (the role that runs these migrations) keeps the schema; the
-- service connects as logistics_app, which reads and writes rows and nothing else, and the collector as monitoring,
-- which reads statistics. Both log in with passwords that afterMigrate.sql sets on every start from their secrets.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'logistics_app') THEN
        CREATE ROLE logistics_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'monitoring') THEN
        CREATE ROLE monitoring NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO logistics_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO logistics_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO logistics_app;
-- Flyway's own table is the owner's alone.
REVOKE ALL ON flyway_schema_history FROM logistics_app;

-- History is appended, never rewritten: a shipment's tracking events (ADR 0009) and which shipments a carrier's event
-- reached (ADR 0037).
REVOKE UPDATE, DELETE ON tracking_events, dcsa_event_shipments FROM logistics_app;
-- Reference data comes only from reviewed migrations, with its source and review date (docs/tariffs/, ADR 0036): the
-- service reads it.
REVOKE INSERT, UPDATE, DELETE ON tariffs, locations, customs_tariff_sources, customs_tariff_lines FROM logistics_app;

-- Tables a later migration creates get the same rights; an append-only one revokes them in its own migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO logistics_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO logistics_app;

-- Statistics only (pg_stat_*, pg_database_size), for the collector's postgresql receiver.
GRANT pg_monitor TO monitoring;
