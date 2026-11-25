-- Flyway runs this after every migrate, as the owner (ADR 0041): banking_app and monitoring log in with the passwords
-- from their secrets (spring.flyway.placeholders), so a rotated secret needs no migration and no password is ever in a
-- migration. Plain statements, not a DO block: Flyway leaves placeholders inside a dollar-quoted body as they are.
-- The secrets are hex (scripts/generate_secrets.py), so a quote cannot end the literal. An empty password sets
-- none: with no collector, monitoring cannot log in.
ALTER ROLE banking_app WITH LOGIN PASSWORD '${app-password}';
ALTER ROLE monitoring WITH LOGIN PASSWORD '${monitoring-password}';
