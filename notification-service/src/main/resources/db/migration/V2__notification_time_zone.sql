-- The organisation's time zone (ADR 0027): dates in its emails are its calendar dates, not UTC's. An IANA region id,
-- checked by the service against the JVM's zone rules. Rows saved before this column read as UTC, as they were.
ALTER TABLE notification_settings ADD COLUMN time_zone VARCHAR(64) NOT NULL DEFAULT 'UTC';
