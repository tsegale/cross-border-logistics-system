# Slo: email delivery

Alerts: EmailDeliveryBudgetBurnFast, EmailDeliveryBudgetBurnSlow

**What it means.** More than 1 % of emails take longer than 60 s from the booking's commit to the mail server's
acceptance (docs/slo.md). Measured: p99 under 0.7 s, and under 13 s after a 30 s broker outage
(docs/benchmarks/events.md).

**Check, stage by stage on [Booking to email](http://127.0.0.1:3000/d/booking-to-email).**
1. Outbox backlog and oldest row: logistics' relay not publishing, see [outbox-stuck](outbox-stuck.md).
2. Consumer lag: notification not consuming, see [consumer-lag](consumer-lag.md).
3. Emails PENDING rising, sent per minute falling: the mail server. `{service_name="notification-service"} |= "refused"`.
4. A slow email's trace (exemplar on "Booking to email p99") shows which hop waited.

**Fix.** The stage that waits. Emails wait in their table and go out once the cause is gone; none is lost.
**Escalate** to the notification owner if the mail relay is fine and emails still wait.
