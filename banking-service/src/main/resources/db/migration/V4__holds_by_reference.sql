-- Operators and the fault-injection tests look holds up by reference (GET /api/v1/holds?reference=).
CREATE INDEX idx_holds_reference ON holds (reference, created_at DESC);
