# Events: booking to email, outbox lag, broker outage (ADR 0025, ADR 0027)

## Question
How long after a customer books does its "Sendung gebucht" email arrive, how much of that is the outbox relay, and
what happens to events and emails when the broker is down?

## Method
`benchmarks/events_to_email.py`, through the gateway, on the default stack with `benchmarks/compose.bench.yml`
(Mailpit's API, banking's and logistics' actuators published on 127.0.0.1):

- Hanseatic Imports saves German notification settings with one recipient; an operator opens a EUR account for it
  and funds it through a four-eyes deposit.
- 10 s of bookings at the rate, discarded (JIT warm-up), then **2 bookings per second for 120 s** (240 shipments),
  each a quote and a `POST /api/v1/shipments` as `customer-hanseatic-imports`, each in its own thread so one slow
  booking does not slow the rate.
- **Booking to email**: from the booking's `201` to Mailpit's received time for its "Sendung gebucht" email; both
  clocks are the runner's. It covers logistics' outbox and relay, Kafka, notification-service's consumer, rendering,
  the email sender's poll (500 ms) and SMTP.
- **Outbox lag**: `freightflow_outbox_lag_seconds`, commit to broker acknowledgement, from the histogram buckets'
  change across the steady phase: exactly the events of these bookings. A quantile is reported as the upper bound of
  its bucket ("at most").
- **Broker outage**: bookings continue at 2/s while `docker compose stop kafka` holds the broker down for 30 s, then
  `start kafka`; measured from the restart until both outboxes report an empty backlog, and until the last email of
  the outage arrived.

## Results
Hosted runner, GitHub `ubuntu-24.04`: Intel Xeon 6975P-C, 4 vCPUs, 15,701 MiB, Docker 28.0.4. Two runs:
[44744962828](https://github.com/tsegale/cross-border-logistics-system/actions/runs/44744962828) (after every other
suite) and [44750794366](https://github.com/tsegale/cross-border-logistics-system/actions/runs/44750794366) (alone).

**Booking to email, 2 bookings/s for 120 s**

| Run | Emails | p50 ms | p95 ms | p99 ms | max ms | Missing |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 44744962828 | 240 | 361 | 612 | 674 | 705 | 0 |
| 44750794366 | 240 | 368 | 618 | 658 | 734 | 0 |

**Outbox lag, commit to broker acknowledgement** (run 44750794366; run 44744962828 read Micrometer's client-side
quantile gauges, which reported 0 and are not used)

| Relay | Events | p50 ms at most | p95 ms at most | p99 ms at most |
| --- | ---: | ---: | ---: | ---: |
| logistics (`shipment.booked`, `shipment.status-changed`) | 480 | 111.8 | 201.3 | 223.7 |
| banking (`hold.authorised`) | 240 | 134.2 | 223.7 | 223.7 |

**Broker down for 30 s, bookings continuing**

| Run | Outage s | Backlog at restart | Restart to outboxes empty s | Bookings during | Last email after restart s | Missing emails |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 44744962828 | 30.2 | 186 | 6.1 | 80 | 6.6 | 0 |
| 44750794366 | 30.3 | 187 | 7.7 | 80 | 9.9 | 0 |

## Reading
- The relay's lag is bounded by its poll interval (200 ms): an event waits on average half of it, then a few
  milliseconds for the broker. That is about a third of booking to email; the rest is notification's consumer and
  its sender's 500 ms poll.
- During the outage nothing was lost: bookings kept answering `201`, their events waited in the outboxes (186 rows,
  booking, status change and hold events), and every email arrived within 10 s of the broker's restart. Most of
  those seconds are the clients reconnecting and the relay's backoff (1 s doubling, with jitter) running out.
- At the end of the run the twelve containers used 3.2 GiB on the runner, with no memory limits set.
