-- Least privilege in the database (ADR 0041). The owner (the role that runs these migrations) keeps the schema; the
-- service connects as notification_app, which reads and writes rows and nothing else, and the collector as monitoring,
-- which reads statistics. Both log in with passwords that afterMigrate.sql sets on every start from their secrets.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'notification_app') THEN
        CREATE ROLE notification_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'monitoring') THEN
        CREATE ROLE monitoring NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO notification_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO notification_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO notification_app;
-- Flyway's own table is the owner's alone.
REVOKE ALL ON flyway_schema_history FROM notification_app;

-- Tables a later migration creates get the same rights; an append-only one revokes them in its own migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO notification_app;

-- Statistics only (pg_stat_*, pg_database_size), for the collector's postgresql receiver.
GRANT pg_monitor TO monitoring;
