-- Least privilege in the database (ADR 0041). The owner (the role that runs these migrations) keeps the schema; the
-- service connects as banking_app, which reads and writes rows and nothing else, and the collector as monitoring,
-- which reads statistics. Both log in with passwords that afterMigrate.sql sets on every start from their secrets.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'banking_app') THEN
        CREATE ROLE banking_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'monitoring') THEN
        CREATE ROLE monitoring NOLOGIN;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO banking_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO banking_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO banking_app;
-- Flyway's own table is the owner's alone.
REVOKE ALL ON flyway_schema_history FROM banking_app;

-- The ledger is append-only (ADR 0004): the triggers refuse an UPDATE or DELETE, and now the role may not
-- even attempt one. A correction is a reversing entry. Reference rates are decided once (an UPDATE the trigger checks)
-- and never deleted (ADR 0034).
REVOKE UPDATE, DELETE ON journal_entries, postings, hold_extensions, system_balance_checkpoints, customs_settlements
    FROM banking_app;
REVOKE DELETE ON reference_rates FROM banking_app;

-- Tables a later migration creates get the same rights; an append-only one revokes them in its own migration.
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO banking_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT USAGE, SELECT ON SEQUENCES TO banking_app;

-- Statistics only (pg_stat_*, pg_database_size), for the collector's postgresql receiver.
GRANT pg_monitor TO monitoring;
