-- Carrier feeds miss scans: an event that skips milestones forward is applied; one that would move the shipment
-- backwards or is not allowed is kept in the history with applied_to_status = false and the reason (ADR 0009).

ALTER TABLE tracking_events
    ADD COLUMN outcome          VARCHAR(16),
    ADD COLUMN outcome_reason   VARCHAR(300),
    ADD COLUMN skipped_statuses VARCHAR(200);

UPDATE tracking_events
SET outcome = CASE WHEN applied_to_status THEN 'APPLIED' ELSE 'OUT_OF_ORDER' END,
    outcome_reason = CASE WHEN applied_to_status THEN NULL ELSE 'Occurred before the latest applied event' END;

ALTER TABLE tracking_events ALTER COLUMN outcome SET NOT NULL;
ALTER TABLE tracking_events ADD CONSTRAINT chk_tracking_events_outcome CHECK (
    (outcome = 'APPLIED' AND applied_to_status AND outcome_reason IS NULL)
    OR (outcome IN ('OUT_OF_ORDER', 'REJECTED') AND NOT applied_to_status AND outcome_reason IS NOT NULL
        AND skipped_statuses IS NULL)
);
