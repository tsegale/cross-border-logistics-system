# Emails unknown

Alerts: EmailsUnknown

**What it means.** A sender stopped while it was handing an email to the mail server (ADR 0027). The email may or may
not have arrived; it is never resent on its own, so a customer may have missed it.

**Check.** `GET /api/v1/notifications?status=UNKNOWN` as an operator or auditor: which organisation, which email
(subject, event). The notification service's restart around `createdAt`: `{service_name="notification-service"}`.

**Fix.** Ask the customer, or the mail relay's logs, whether it arrived. If not, resend it, acknowledging it may arrive
twice: `POST /api/v1/notifications/{id}/resend` with `{"acknowledgeDuplicate": true}` (recorded with the operator).
**Escalate** if UNKNOWN emails keep appearing: the sender is being killed, check its memory (ADR 0029).
