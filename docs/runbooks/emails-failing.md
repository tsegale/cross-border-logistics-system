# Emails failing

Alerts: EmailsFailing

**What it means.** The mail server refused an email on every attempt (6, with backoff); it is `FAILED` (ADR 0027).

**Check.** `{service_name="notification-service"} |= "giving up"`: the server's answer (addresses are masked). The
notification's `lastError` in `GET /api/v1/notifications?status=FAILED`.

**Fix.** A rejected address: correct the organisation's recipients (`PUT /api/v1/notification-settings`). The relay's
credentials or TLS: fix them; new emails go out, the failed one is not resent (the next event sends the next email).
**Escalate** to whoever runs the mail relay.
