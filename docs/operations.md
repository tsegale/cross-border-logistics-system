# Operations: the signals and when to act

Every service serves Prometheus metrics on `/actuator/prometheus` (the services' port on the compose network; the
gateway's management port 8085). Phase 5 builds the dashboards and the alert rules; this page fixes what each signal
means and the one condition that should page someone. Names are as Prometheus shows them.

## Events (ADR 0025, ADR 0026)

| Signal | Metric | Alert when | Why that condition |
| --- | --- | --- | --- |
| Outbox backlog | `freightflow_outbox_backlog` (banking; logistics with `outbox="events"` and `outbox="payment-commands"`) | above 1000 for 5 minutes | a relay that keeps up holds a handful; a growing backlog means the broker or the relay is down, and every consumer is falling behind with it |
| Outbox age | `freightflow_outbox_oldest_age_seconds` | above 60 s for 5 minutes | measured lag is milliseconds (docs/benchmarks/events.md); a minute means nothing is being published, whatever the backlog size |
| Outbox lag | `freightflow_outbox_lag_seconds` (histogram: commit to broker acknowledgement) | p95 above 5 s over 10 minutes | the relay is slow rather than stopped: a sick broker, or a relay starved by the database |
| Send failures | `freightflow_outbox_send_failures_total` | any increase for 5 minutes in a row | one failure is a blip; failures every minute mean the broker or an ACL is wrong |
| Consumer lag | `kafka_consumer_fetch_manager_records_lag_max` (by `client_id`; groups `logistics-service`, `notification-service*`) | above 1000 for 10 minutes | consumers handle tens of events a second; a thousand behind for ten minutes is a consumer that stopped or is failing every record |
| Dead letters (notification) | `freightflow_dead_letters{status="NEW"}` | above 0 for 15 minutes | every dead letter is an event a customer may not have been told about; an operator redrives or explains it |
| Dead letters (logistics) | `freightflow_dead_letters{status="NEW"}` on logistics-service | above 0 for 15 minutes | an expiry or capture logistics could not act on: an operator redrives it (`POST /api/v1/logistics/dead-letters/{id}/redrive`) or re-authorises the hold |
| Unexpected captures | `freightflow_payment_unexpected_captures_total` | any increase | money left a customer account without a pickup logistics asked for (ADR 0028) |

## Emails (ADR 0027)

| Signal | Metric | Alert when | Why that condition |
| --- | --- | --- | --- |
| Sent | `freightflow_emails_sent_total` (by `kind`, `language`) | none on its own; the rate is the denominator for the next row | |
| Failed | `freightflow_emails_failed_total{final="true"}` | more than 1 % of sent over an hour | single refusals are retried; final failures at that rate mean the relay, its credentials or the addresses are wrong |
| Waiting | `freightflow_emails{status="PENDING"}` | above 500 for 15 minutes | the sender stopped or the SMTP server is down; customers are not being told |
| Unknown | `freightflow_emails{status="UNKNOWN"}` | any increase | a sender died mid-send; the customer may have missed that email and support should know |
| Delay | `freightflow_emails_delay_seconds` (queued to accepted by SMTP) | p95 above 60 s over 30 minutes | normally under a second; a minute is a struggling relay |

## Payments (ADR 0012)

| Signal | Metric | Alert when |
| --- | --- | --- |
| Parked commands | `freightflow_payment_commands_parked` | above 0: an operator decides |
| Expired holds | `freightflow_holds_expired_total` | a rise right after a banking outage: logistics re-authorises them (ADR 0028), check `freightflow_events_consumed_total{type="hold.expired",outcome="reauthorising"}` follows |
