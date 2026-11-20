# 27. notification-service: emails in German and English, at most once

Date: 2026-11-20 · Status: Accepted

## Context
Customers want to know when a shipment is booked, moves, is delivered, and what happened to their money. FreightFlow's
customers are German importers and Namibian and South African exporters: emails in German and English, amounts and
dates the way each reader writes them. Until Phase 4 notification-service was a skeleton.

Two guarantees matter more than speed: an email for an event is sent at most once (a duplicate "Sendung gebucht" looks
like a duplicate booking), and no event is lost on the way (the outbox and the consumer guarantees, ADR 0025, ADR 0026).

## Decision
**Its own service and database** (ADR 0001): `notification_settings`, `notifications`, `shipments` (a projection of
logistics' events), `processed_events`, `dead_letters`, Flyway migrations, JDBC.

**Inputs.** `freightflow.shipment.v1` (booked, status changed, payment failed) and `freightflow.hold.v1` (captured,
released, expired), as group `notification-service` (ACLs, ADR 0022). Retry topics and dead letters as ADR 0026
describes. A hold event names its shipment only by reference; the email needs the tracking number, so the projection
built from `shipment.booked` provides it, and a hold event that arrives first is retried until the booking is there.

**Who and how.** An organisation's customer sets `GET/PUT /api/v1/notification-settings`: language (`de`, `en`), up to
ten recipients, and which kinds (`shipment-booked`, `shipment-status`, `payment-failed`, `payment-captured`,
`payment-released`). The organisation comes from the token, never the request (ADR 0018); `ETag` and `If-Match` guard
concurrent edits; who changed it is recorded (ADR 0020). An organisation without settings gets nothing: there is no
recipient to guess. Recipients are the only personal data the service holds, entered by their owner, not taken from
any event (ADR 0024); notification history is deleted after 90 days.

`GET /api/v1/notifications` lists an organisation's emails (customers: their own; operators and auditors: any).
The gateway routes both and checks roles first (ADR 0015, 0017); the service checks again.

**Templates.** One Thymeleaf layout, HTML (`mail/html/notification.html`) and plain text (`mail/text/notification.txt`),
with every word from `mail/messages.properties` (English, the base) and `messages_de.properties`. Each kind has a
subject, an opening sentence and a few labelled facts. Amounts and dates are formatted in Java for the locale before
the template sees them: de-DE `1.840,50 €` and `25.11.2026`, en-GB `€1,840.50` and `25 Nov 2026`, rounded half-even to
the currency's minor units (CLAUDE.md money rules). Status names and banking's refusal reasons are translated.

**Rendering and sending are separate.** The consumer renders the email and writes one `notifications` row per
recipient in the transaction that consumes the event, unique on `(event_id, recipient)`: a redelivered event writes
nothing. `EmailSender` sends rows over SMTP (Spring Mail; Mailpit in compose, with SMTP auth from
`secrets/smtp_password`; any relay in production with `SMTP_STARTTLS=true`):

| State | Meaning |
| --- | --- |
| `PENDING` | to send, at `next_attempt_at` |
| `SENDING` | claimed (`FOR UPDATE SKIP LOCKED`), leased for 2 minutes, handed to SMTP |
| `SENT` | the server accepted it |
| `PENDING` again | the server refused: nothing was delivered; backoff 2 s doubling, with jitter, up to 6 attempts |
| `FAILED` | refused 6 times; counted, an operator looks |
| `UNKNOWN` | the sender died while `SENDING`: it may have arrived; **never sent again** |

That last row is the "at most once": without it, a sender that crashed after the server accepted the email and before
the row said `SENT` would send it again. A customer may then miss one email (visible as `UNKNOWN` in the history and
the metric) but never gets two. Each email carries `Message-ID: <notification id@notifications.freightflow.dev>` and
`X-FreightFlow-Event-Id`, so a duplicate would at least be recognisable downstream.

**Options not taken.** A hosted email API (SES, SendGrid) with its own idempotency key would give exactly-once at
the provider; it is the production choice once there is a provider account, and `EmailSender` is the only class that
changes. Sending from the consumer thread would tie Kafka throughput to SMTP latency and make a slow SMTP server look
like a failing consumer.

## Consequences
- Mailpit is in the default stack, its UI and API on `--profile debug` (`127.0.0.1:8025`); CI's smoke test reads the
  "Sendung gebucht" email through its API inside the network.
- Metrics: `freightflow.emails.sent` and `freightflow.emails.failed` (by kind), gauges `freightflow.emails{status}`
  for PENDING, FAILED and UNKNOWN, `freightflow.emails.delay`, `freightflow.dead-letters`; alerts in
  docs/operations.md.
- A new kind of email is a message key pair, a `NotificationKind` and a branch in `EventNotifier`; the layout stays.
- Per-organisation time zones are not modelled: dates are UTC calendar dates, which is what the services store.
