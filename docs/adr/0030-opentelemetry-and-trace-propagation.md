# 30. OpenTelemetry in every service, and one trace per booking through outboxes and Kafka

Date: 2026-11-21 · Status: Accepted · Builds on ADR 0015 (request ids), ADR 0024 (no personal data), ADR 0025

## Context
One booking crosses the gateway, logistics, two tables written in one transaction (the event outbox and the payment
commands), banking over HTTP from a relay thread, banking's own outbox, Kafka, logistics' and notification's
consumers, and notification's email table, sent later by another thread. Until now each service logged on its own
with a request id (ADR 0015) that stopped at every asynchronous hop. When an email was late, nothing showed which hop
had held it.

Automatic propagation covers HTTP and, with Spring Kafka's observation, Kafka records. It cannot cover a row: the
transaction that writes it ends, and whatever sends it later has no request context left.

## Decision
**Spring Boot 4's own OpenTelemetry support in all four JVMs, not the OpenTelemetry Java agent.**
`spring-boot-starter-opentelemetry` brings the SDK, the OTLP exporters and the Micrometer tracing bridge, and every
observation Spring already makes (MVC and WebFlux servers, `RestClient`, Kafka templates and listeners) becomes a
span. The agent would add instrumentation for more libraries (JDBC statements, Lettuce) at the cost of a second
instrumentation model, a startup penalty and memory in every container (ADR 0029). Logs over OTLP are not part of Boot:
each JVM installs `opentelemetry-logback-appender-1.0`, in `TelemetryConfig`. The appender is built on one
OpenTelemetry API, and must be the release built on the one Boot manages: 2.34.0-alpha on 1.68.0 for Boot 4.2.0
(it was 2.28.0-alpha on 1.62.0 for Boot 4.1.2). A mismatch starts no context: `NoClassDefFoundError` inside
`OpenTelemetrySdkAutoConfiguration` (Dependabot #11) or `ClassNotFoundException` for an incubator class (#8). So
each pom declares the API its appender is built on (`opentelemetry-logback-appender.built-on`, from the appender's
pom), and a Maven Enforcer `requireProperty` rule fails the build, naming both versions, unless it equals Boot's
`opentelemetry.version`. Enforcer's `requireUpperBoundDeps` was tried and does not see the mismatch: it skips versions
set by dependency management, which is how Boot sets every OpenTelemetry artifact.

- **Export is off unless the `otlp` Spring profile is on** (the observability compose profile sets it, ADR 0032).
  Spans are still created and sampled, so trace and span ids reach every log line and every outbox row without a
  backend running. The profile is not an empty default in `application.yml`: Boot creates the log exporter whenever its
  endpoint property exists, and an empty endpoint fails startup.
- **Resource attributes** on every signal: `service.name` (`spring.application.name`), `service.version`
  (`SERVICE_VERSION`: compose passes it on, CI sets it to the commit sha) and `deployment.environment.name`
  (`DEPLOYMENT_ENVIRONMENT`, `local` by default).
- **Metrics are not pushed over OTLP.** The collector scrapes each service's `/actuator/prometheus`, which already
  exists, carries exemplars, and keeps one path for metrics (ADR 0031).
- **Noise is dropped at the source**: no spans for `@Scheduled` polls, actuator requests or Spring Security's filter
  chain. A relay polling five times a second would otherwise produce most of the traces in the system.

**Sampling in the services is "keep everything".** Services use `parent-based-always-on`, so they follow the
gateway; the gateway uses `always-on`, so a caller's `sampled=0` cannot switch tracing off for its request. The
decision of what to keep is the collector's, after the trace is complete (tail sampling, ADR 0031).

**Every row that is sent later stores the W3C `traceparent` of the transaction that wrote it** (`TraceContext`, one
copy per service: ADR 0002): logistics' `event_outbox` and `shipment_payments`, banking's `event_outbox`,
notification's `notifications`. A `CHECK` holds the column to the W3C format. The sender opens a span that continues
it:

    gateway  POST /api/v1/shipments
      logistics  http post /api/v1/shipments           writes event_outbox + shipment_payments (traceparent)
        logistics  payment-command send                 continues shipment_payments.traceparent
          http post (RestClient)  ->  banking  http post /api/v1/holds   writes event_outbox (traceparent)
            banking  event-outbox relay  ->  kafka send  ->  freightflow.hold.v1
              logistics / notification  receive        Kafka observation, from the record header
        logistics  event-outbox relay                   continues event_outbox.traceparent
          kafka send  ->  freightflow.shipment.v1  ->  notification  receive   writes notifications (traceparent)
            notification  email send                    continues notifications.traceparent

Retry and dead-letter topics: the forwarded record keeps the original's headers, `traceparent` included, and is sent
by the same observed template, so a retried event stays in its trace. `RetryTraceIntegrationTest` (notification) proves
it: a hold release that fails because its shipment is not known yet goes through a retry topic, succeeds there once
the booking has arrived, and its email is sent in the trace the hold event carried, with consumer spans on the main
and the retry topic.

**Parent and child, not span links, for every asynchronous hop.** The OpenTelemetry messaging conventions describe
both: a link when one consumer span handles many messages (a batch) or when the work should be its own trace, a
parent when one message is processed on its own. Here every hop moves one row or one record, the consumers use
`ack-mode: record`, and the gap between hops is milliseconds to seconds (ADR 0025's relay polls every 200 ms), so
one booking reads as one waterfall, which is what an operator following it needs. Two cases would get links instead,
and none exists yet: a batch consumer, and work that a scheduled job starts long after the request that caused it.
The hold renewal job (ADR 0011) and the hold expiry sweep start their own traces, since no request caused them.

**Domain attributes, ids only.** `freightflow.shipment.id`, `freightflow.hold.id`, `freightflow.journal_entry.id`,
`freightflow.approval.id`, `freightflow.account.id`, `freightflow.event.type`, `freightflow.event.id`,
`freightflow.payment_command.type` and `.outcome`, `freightflow.notification.kind`, and `freightflow.money.moved` on
every span that posted a journal entry (the collector keeps those traces, ADR 0031). Each event a transaction writes
is also a span event. **Never** a recipient, a party's name or contact, a request body or a token: ADR 0024 applies to
telemetry as it does to events. An SMTP refusal quotes the recipient, so notification masks addresses before an error
reaches a log line or a span (`Redaction`).

**Logs carry `trace_id` and `span_id`** (Spring's ECS JSON with the two MDC keys renamed to OpenTelemetry's names),
and still `requestId`. **The request id stays, and is on the server span as `freightflow.request_id`.** They answer
different questions: the trace id follows the work, across threads, the outbox and Kafka, but a caller never sees it;
the request id is in every response (`X-Request-Id`), so it is what a customer or a support ticket quotes. From it,
Grafana finds the trace; from the trace, the logs of every service.

## Proof
- `BookingTraceIntegrationTest` (logistics): a booking sent with a gateway's `traceparent` leaves that trace id in
  both outbox tables, on the HTTP call to banking and on every Kafka record of the shipment; the spans
  `http post /api/v1/shipments`, `payment-command send` and `event-outbox relay` are in it, with the shipment's id.
- `HoldTraceIntegrationTest` (banking): a hold requested in logistics' trace writes it to `hold.authorised`'s row, and
  the relay publishes in it; the server span carries `freightflow.hold.id` and `freightflow.request_id`.
- `NotificationFlowIntegrationTest` (notification): a record with a `traceparent` header becomes a notification row
  with it, and the email's `email send` span is in the same trace, after Kafka's consumer span.
- In all three, no span attribute, span event or captured log line holds an email address or a bearer token
  (`TelemetryLeaks`, whose own test proves it finds both). The booking used in the logistics test names two contact
  addresses; neither left the service in telemetry.
- CI then checks the real thing: one booking's trace in Tempo with every expected span, its logs in Loki and the
  business metrics in Prometheus (ADR 0032).

## Consequences
- **Four columns and one helper class per service** carry what HTTP and Kafka carry on their own. A new table that
  something sends later needs the same column, or its sends start new traces.
- **Spans cost little but not nothing**: Boot's batch processor keeps up to 2,048 spans in memory per JVM before it
  drops, and the in-process SDK is part of each service's measured memory (ADR 0029).
- **SQL statements are not spans.** The relays' and requests' database time shows only as the gap inside their
  spans; datasource-micrometer or the agent would add statement spans if a slow query needs finding by trace.
- A caller can still choose the trace id of its own request by sending a `traceparent` to the gateway. It cannot turn
  sampling off, and the trace id gives it nothing it did not already have; the threat model records it.
