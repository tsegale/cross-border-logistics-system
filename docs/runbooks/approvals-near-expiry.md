# Approvals near expiry

Alerts: ApprovalsNearExpiry

**What it means.** A deposit or reversal requested by one operator waits for a second, and expires within the hour
(ADR 0021). Expired, it must be requested again.

**Check.** [Money](http://127.0.0.1:3000/d/money), "Approvals waiting"; the list: `GET /api/v1/approvals?status=PENDING` as an operator or
auditor.

**Fix.** A second operator (not the requester) reviews and approves or rejects it:
`POST /api/v1/approvals/{id}/approval` or `/rejection`. **Escalate** to the operations lead if no second operator is on
shift.
