# Carrier tracking: refused notifications, polling behind

Alerts: CarrierWebhookSignaturesFailing, CarrierWebhooksRateLimited, CarrierPollingBehind

**What it means.** Carriers report container events in DCSA Track and Trace 2.2 (ADR 0037).
- **CarrierWebhookSignaturesFailing.** logistics refused at least one notification in 10 minutes. The causes:
  - an unknown carrier or Subscription-ID;
  - a Notification-Signature that is not the HMAC-SHA256 of the body with the subscription's secret;
  - an event created more than 24 hours ago, or in the future.

  Nothing from a refused notification is stored. Either a carrier is misconfigured, typically after a secret
  rotation on one side only, or someone is sending forged tracking events.
- **CarrierWebhooksRateLimited (ticket).** The gateway answered 429 to carrier notifications in the last 10 minutes,
  before logistics saw them: the webhook has no token, so it is budgeted per carrier in the path
  (`RATE_LIMIT_WEBHOOK_CARRIER_RPS`, `_BURST`) and per source address (`RATE_LIMIT_WEBHOOK_SOURCE_RPS`, `_BURST`,
  ADR 0041). The `key` label says which. A real carrier retries a 429; one that keeps hitting its limit needs a
  higher one, agreed with it. A source limit hit by an address that is no carrier is someone probing the webhook:
  the gateway's logs name the route and the address. Bodies over 256 KB are refused with 413 and never counted here.
- **CarrierPollingBehind (ticket).** A carrier configured `mode: POLL` has not answered `GET /v2/events` for over 5
  minutes. Its shipments' milestones are not moving.

**Check.**
- [Flow](http://127.0.0.1:3000/d/flow), "Carriers": notifications by outcome, events processed by outcome, and the
  poll age.
- logistics' logs: `DCSA notification refused` (signature or subscription) and `Polling DCSA events of carrier ...
  failed` with the cause.
- The stored events, by status:
  `SELECT carrier_id, status, count(*) FROM dcsa_events GROUP BY 1, 2;`
- Where polling stopped:
  `SELECT * FROM dcsa_poll_cursors;`

**Fix.**
- **Signature failures from one carrier, all of them.** The two sides have different secrets. Agree the secret with
  the carrier, then replace `secrets/dcsa_carrier_secret` and their subscription secret together, through
  `PUT /v2/event-subscriptions/{id}/secret` on their side. Then restart logistics-service. Their events of the
  meantime arrive through polling, or when the carrier resends them within 24 hours.
- **Occasional failures from unknown sources.** Somebody is probing the endpoint. Nothing was stored; check the
  gateway's access logs for the source and block it there.
- **Replays refused.** A carrier resent day-old notifications. The poller picks those events up if the carrier is
  polled; otherwise ask the carrier for the events again.
- **Polling behind.** Check the carrier's API status. The `dcsa-poll` breaker stops calls for 2 minutes after
  repeated failures. When the carrier is back, the next poll continues from the cursor and loses nothing.

**Escalate** to security if the refused notifications come from somewhere that is not the carrier.
