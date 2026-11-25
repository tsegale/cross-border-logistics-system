#!/usr/bin/env python3
"""Events benchmark (ADR 0025, ADR 0027): booking to email delivered, outbox lag, and recovery after a broker outage.

Books shipments through the gateway as Hanseatic Imports (German emails) at a steady rate and measures, per booking,
the time from the booking's 201 to Mailpit receiving its "Sendung gebucht" email (both clocks are the runner's).
Reads the outbox relays' lag (commit to broker acknowledgement) from banking and logistics. Then keeps booking while
Kafka is stopped for --outage seconds, and measures from the broker's restart until both outboxes are empty and
every email of the outage has arrived.

With --cdc (the stack also with benchmarks/compose.cdc.yml) it compares logistics' polling relay with Debezium reading
the same outbox from the write-ahead log: for each event of the run, the time from its occurredAt to the broker's
append time on freightflow.shipment.v1 (the relay) and on freightflow.shipment.v1.cdc (Debezium). Both topics are set
to LogAppendTime for this, so both are the broker's clock, which is the host's, like the services'.

Needs the stack with benchmarks/compose.bench.yml (it publishes Mailpit, logistics and banking's actuators on
127.0.0.1) and docker compose on the PATH. Standard library only.

    python3 benchmarks/events_to_email.py --rate 2 --seconds 120 --outage 30 --json out.json
"""
from __future__ import annotations

import argparse
import json
import os
import re
import statistics
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import date, datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import keycloak_token  # noqa: E402

GATEWAY = os.environ.get("GATEWAY_URL", "http://127.0.0.1:8080")
MAILPIT = os.environ.get("MAILPIT_URL", "http://127.0.0.1:8025")
ACTUATORS = {"banking": "http://127.0.0.1:8081", "logistics": "http://127.0.0.1:8082"}
COMPOSE = os.environ.get("COMPOSE", "docker compose -f docker-compose.yml -f benchmarks/compose.bench.yml").split()
HANSEATIC = "0193a0e0-0000-7000-8000-00000000a002"
QUOTE = json.dumps({
    "origin": "NAWVB", "destination": "DEHAM", "mode": "SEA_LCL", "incoterm": "FCA", "payerRole": "CONSIGNEE",
    "packages": [{"lengthCm": 120, "widthCm": 100, "heightCm": 150, "weightKg": 500, "quantity": 2}],
})


class Tokens:
    """A persona's token, renewed after four minutes (they live five, ADR 0016)."""

    def __init__(self) -> None:
        self._cache: dict[str, tuple[float, str]] = {}
        self._lock = threading.Lock()

    def get(self, persona: str) -> str:
        with self._lock:
            fetched, token = self._cache.get(persona, (0.0, ""))
            if time.monotonic() - fetched > 240:
                token = keycloak_token.token_for(persona)
                self._cache[persona] = (time.monotonic(), token)
            return token

    def forget(self, persona: str) -> None:
        with self._lock:
            self._cache.pop(persona, None)


TOKENS = Tokens()


def call(persona: str, method: str, path: str, body: dict | str | None = None, headers: dict | None = None,
         expect: int = 200) -> dict:
    data = None if body is None else (body if isinstance(body, str) else json.dumps(body)).encode()
    request = urllib.request.Request(GATEWAY + path, data=data, method=method, headers={
        "Authorization": f"Bearer {TOKENS.get(persona)}", "Content-Type": "application/json",
        "Accept": "application/json", **(headers or {})})
    with urllib.request.urlopen(request, timeout=30) as response:
        if response.status != expect:
            raise RuntimeError(f"{method} {path}: {response.status}, expected {expect}")
        return json.loads(response.read() or b"{}")


def get_json(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=10) as response:
        return json.loads(response.read())


def metric(service: str, name: str, labels: str = "") -> float:
    """Sum of a Prometheus metric's samples whose label set contains `labels`."""
    with urllib.request.urlopen(ACTUATORS[service] + "/actuator/prometheus", timeout=10) as response:
        total = 0.0
        for line in response.read().decode().splitlines():
            if line.startswith(name) and (line[len(name)] in "{ ") and labels in line:
                total += float(line.rsplit(" ", 1)[1])
        return total


def mail_received(recipient: str) -> dict[str, datetime]:
    """Tracking number -> when Mailpit received its booking email."""
    query = urllib.parse.quote(f'to:"{recipient}" subject:"Sendung gebucht"')
    received: dict[str, datetime] = {}
    start = 0
    while True:
        page = get_json(f"{MAILPIT}/api/v1/search?query={query}&limit=500&start={start}")
        for message in page["messages"]:
            tracking = message["Subject"].rsplit(" ", 1)[1]
            received[tracking] = datetime.fromisoformat(message["Created"].replace("Z", "+00:00"))
        start += len(page["messages"])
        if not page["messages"] or start >= page["messages_count"]:
            return received


def percentile(values: list[float], p: float) -> float:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, round(p / 100 * len(ordered) + 0.5) - 1))]


def setup(run: str) -> tuple[str, str]:
    """German settings, and a funded EUR account for Hanseatic (four eyes, ADR 0021)."""
    recipient = f"bench-{run}@hanseatic-imports.example"
    settings = {"language": "de", "recipients": [recipient],
                "kinds": ["shipment-booked", "shipment-status", "payment-failed", "payment-captured",
                          "payment-released"]}
    keycloak_token.wait_until_serving()
    # Straight after a start the first token can be refused; retry with a fresh one, and say so.
    for attempt in range(1, 31):
        try:
            call("customer-hanseatic-imports", "PUT", "/api/v1/notification-settings", settings)
            break
        except urllib.error.HTTPError as error:
            if error.code != 401 or attempt == 30:
                raise
            print(f"setup: first call refused with 401 (attempt {attempt}), retrying with a fresh token")
            TOKENS.forget("customer-hanseatic-imports")
            time.sleep(2)
    account = call("operator", "POST", "/api/v1/accounts", {"currency": "EUR", "organizationId": HANSEATIC},
                   expect=201)["accountNumber"]
    approval = call("operator", "POST", "/api/v1/approvals", {
        "kind": "DEPOSIT", "account": account, "amount": 10_000_000.00, "currency": "EUR",
        "reference": f"BENCH-{run}"}, headers={"Idempotency-Key": f"bench-dep-{run}"}, expect=201)["id"]
    call("second-operator", "POST", f"/api/v1/approvals/{approval}/approval", {"comment": "benchmark"},
         headers={"Idempotency-Key": f"bench-approve-{run}"})
    return recipient, account


def book(account: str, run: str, index: int) -> tuple[str, datetime]:
    quote = call("customer-hanseatic-imports", "POST", "/api/v1/quotes", QUOTE, expect=201)["id"]
    pickup = (date.today() + timedelta(days=7)).isoformat()
    shipment = call("customer-hanseatic-imports", "POST", "/api/v1/shipments", {
        "quoteId": quote,
        "shipper": {"name": "Namib Fisheries", "address": "Walvis Bay", "contact": "x"},
        "consignee": {"name": "Hanseatic Seafood", "address": "Hamburg", "contact": "y"},
        "payerAccountNumber": account, "goodsDescription": "Frozen hake", "declaredValue": 18500.00,
        "declaredValueCurrency": "EUR", "plannedPickupDate": pickup,
    }, headers={"Idempotency-Key": f"bench-ship-{run}-{index}"}, expect=201)
    return shipment["trackingNumber"], datetime.now(timezone.utc)


def steady(account: str, run: str, rate: float, seconds: float, offset: int) -> dict[str, datetime]:
    """Bookings at `rate` per second for `seconds`, each in its own thread so a slow one does not slow the rate."""
    booked: dict[str, datetime] = {}
    errors: list[str] = []
    lock = threading.Lock()

    def one(index: int) -> None:
        try:
            tracking, at = book(account, run, index)
            with lock:
                booked[tracking] = at
        except Exception as error:  # a failed booking is reported, not hidden
            with lock:
                errors.append(f"{index}: {error}")

    threads = []
    start = time.monotonic()
    count = int(rate * seconds)
    for i in range(count):
        time.sleep(max(0.0, start + i / rate - time.monotonic()))
        thread = threading.Thread(target=one, args=(offset + i,))
        thread.start()
        threads.append(thread)
    for thread in threads:
        thread.join()
    if errors:
        raise RuntimeError(f"{len(errors)} of {count} bookings failed, first: {errors[0]}")
    return booked


def wait_for_emails(recipient: str, tracking: set[str], timeout: float) -> dict[str, datetime]:
    deadline = time.monotonic() + timeout
    while True:
        received = mail_received(recipient)
        if tracking <= received.keys() or time.monotonic() > deadline:
            return received
        time.sleep(1)


def latencies(booked: dict[str, datetime], received: dict[str, datetime]) -> list[float]:
    return [(received[t] - at).total_seconds() * 1000 for t, at in booked.items() if t in received]


def summary(values: list[float]) -> dict[str, float]:
    return {"count": len(values), "p50_ms": round(percentile(values, 50)), "p95_ms": round(percentile(values, 95)),
            "p99_ms": round(percentile(values, 99)), "max_ms": round(max(values)),
            "mean_ms": round(statistics.fmean(values))}


def lag_buckets(service: str) -> dict[float, float]:
    """The relay's lag histogram: upper bound in seconds -> cumulative count (Prometheus `le` buckets)."""
    buckets: dict[float, float] = {}
    with urllib.request.urlopen(ACTUATORS[service] + "/actuator/prometheus", timeout=10) as response:
        for line in response.read().decode().splitlines():
            if line.startswith("freightflow_outbox_lag_seconds_bucket{"):
                bound = line.split('le="', 1)[1].split('"', 1)[0]
                buckets[float("inf") if bound == "+Inf" else float(bound)] = float(line.rsplit(" ", 1)[1])
    return buckets


def outbox_lag(before: dict[str, dict[float, float]], after: dict[str, dict[float, float]]) -> dict:
    """p50/p95/p99 of the events published between two snapshots, as the upper bound of the bucket each falls in."""
    result = {}
    for service in ACTUATORS:
        delta = sorted((bound, after[service].get(bound, 0) - before[service].get(bound, 0))
                       for bound in after[service])
        total = delta[-1][1] if delta else 0
        quantiles = {"events": int(total)}
        for q in (0.5, 0.95, 0.99):
            bound = next((b for b, cumulative in delta if total and cumulative >= q * total), float("nan"))
            quantiles[f"p{int(q * 100)}_ms_at_most"] = round(bound * 1000, 1)
        result[service] = quantiles
    return result


def backlog() -> float:
    return metric("banking", "freightflow_outbox_backlog") + metric("logistics", "freightflow_outbox_backlog",
                                                                    'outbox="events"')


# A client config for the broker's admin, written inside the broker's container from its secret.
KAFKA_CLIENT = (
    'printf "security.protocol=SASL_SSL\nssl.truststore.type=PEM\nssl.truststore.location=/tls/ca.pem\nsasl.mechanism=SCRAM-SHA-512\nsasl.jaas.config='
    'org.apache.kafka.common.security.scram.ScramLoginModule required username=\\"admin\\" password=\\"%s\\";\n" '
    '"$(cat /run/secrets/kafka_admin_password)" > /tmp/bench-client.properties'
)
RELAY_TOPIC = "freightflow.shipment.v1"
CDC_TOPIC = "freightflow.shipment.v1.cdc"


def kafka(command: str) -> str:
    """Runs a Kafka CLI command in the broker's container as its admin."""
    result = subprocess.run([*COMPOSE, "exec", "-T", "kafka", "bash", "-c", f"{KAFKA_CLIENT} && {command}"],
                            check=True, capture_output=True, text=True)
    return result.stdout


def wait_for_debezium(timeout: float = 300) -> None:
    """Until Debezium streams from its replication slot: before that, new outbox rows would not reach the topic."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = subprocess.run([*COMPOSE, "exec", "-T", "logistics-db", "sh", "-c",
                                 "psql -U \"$POSTGRES_USER\" -d logistics -tAc \"SELECT active FROM "
                                 "pg_replication_slots WHERE slot_name = 'freightflow_benchmark'\""],
                                capture_output=True, text=True)
        if result.stdout.strip() == "t":
            return
        time.sleep(2)
    raise RuntimeError("Debezium's replication slot freightflow_benchmark is not active")


def append_time_topics() -> None:
    for topic in (RELAY_TOPIC, CDC_TOPIC):
        kafka("/opt/kafka/bin/kafka-configs.sh --bootstrap-server kafka:29092 --command-config "
              f"/tmp/bench-client.properties --alter --entity-type topics --entity-name {topic} "
              "--add-config message.timestamp.type=LogAppendTime")


def instant(text: str) -> datetime:
    """Java's Instant.toString(), up to nanoseconds, as a datetime (microseconds)."""
    return datetime.fromisoformat(re.sub(r"(\.\d{6})\d+", r"\1", text.replace("Z", "+00:00")))


def topic_events(topic: str) -> dict[str, tuple[datetime, datetime]]:
    """eventId -> (occurredAt, broker append time) for every record on the topic; the first copy counts."""
    output = kafka("/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 --consumer.config "
                   f"/tmp/bench-client.properties --topic {topic} --from-beginning --timeout-ms 15000 "
                   "--property print.timestamp=true 2>/dev/null || true")
    events: dict[str, tuple[datetime, datetime]] = {}
    for line in output.splitlines():
        if not line.startswith(("LogAppendTime:", "CreateTime:")) or "\t" not in line:
            continue
        stamp, value = line.split("\t", 1)
        envelope = json.loads(value)
        while isinstance(envelope, str):  # Debezium writes a json column as a JSON string
            envelope = json.loads(envelope)
        appended = datetime.fromtimestamp(int(stamp.split(":", 1)[1]) / 1000, timezone.utc)
        events.setdefault(envelope["eventId"], (instant(envelope["occurredAt"]), appended))
    return events


def compare_paths(start: datetime, end: datetime) -> dict:
    """occurredAt to broker append, per event of the window, for the relay and for Debezium."""
    relay, cdc = topic_events(RELAY_TOPIC), topic_events(CDC_TOPIC)
    window = {event: times for event, times in relay.items() if start <= times[0] <= end}
    result: dict = {"events": len(window), "missing_on_cdc": len(window.keys() - cdc.keys())}
    for name, source in (("relay", relay), ("cdc", cdc)):
        lags = [(source[e][1] - source[e][0]).total_seconds() * 1000 for e in window if e in source]
        result[name] = summary(lags) if lags else {"count": 0}
    return result


def last_append_after(times: dict[str, tuple[datetime, datetime]], start: datetime, end: datetime) -> float:
    """Seconds from `end` (the broker's restart) to the append of the last event that occurred in [start, end]."""
    appended = [t[1] for t in times.values() if start <= t[0] <= end]
    return round((max(appended) - end).total_seconds(), 1) if appended else float("nan")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rate", type=float, default=2.0, help="bookings per second")
    parser.add_argument("--seconds", type=float, default=120, help="length of the steady phase")
    parser.add_argument("--outage", type=float, default=30, help="seconds Kafka is stopped; 0 skips the outage")
    parser.add_argument("--json", help="write the results here")
    parser.add_argument("--cdc", action="store_true", help="compare the relay with Debezium (compose.cdc.yml)")
    args = parser.parse_args()
    run = str(int(time.time()))
    if args.cdc:
        wait_for_debezium()
        append_time_topics()

    recipient, account = setup(run)
    # Warm-up, discarded: the first bookings after a start measure the JIT.
    warm = steady(account, run, args.rate, 10, 0)
    wait_for_emails(recipient, set(warm), 120)

    before = {service: lag_buckets(service) for service in ACTUATORS}
    steady_start = datetime.now(timezone.utc)
    booked = steady(account, run, args.rate, args.seconds, 10_000)
    steady_end = datetime.now(timezone.utc)
    received = wait_for_emails(recipient, set(booked), 120)
    lag = outbox_lag(before, {service: lag_buckets(service) for service in ACTUATORS})
    steady_result = summary(latencies(booked, received))
    steady_result["missing"] = len(set(booked) - received.keys())
    print(f"steady {args.rate}/s for {args.seconds:.0f} s: booking to email {steady_result}")
    print(f"outbox lag (histogram buckets, commit to broker ack): {lag}")
    results = {"rate_per_s": args.rate, "seconds": args.seconds, "booking_to_email": steady_result,
               "outbox_lag": lag}
    if args.cdc:
        paths = compare_paths(steady_start, steady_end)
        print(f"occurredAt to broker append, relay vs Debezium: {paths}")
        results["relay_vs_cdc"] = paths

    if args.outage > 0:
        outage_booked: dict[str, datetime] = {}
        loader = threading.Thread(target=lambda: outage_booked.update(
            steady(account, run, args.rate, args.outage + 10, 20_000)))
        loader.start()
        time.sleep(5)
        subprocess.run([*COMPOSE, "stop", "kafka"], check=True, capture_output=True)
        stopped = time.monotonic()
        stopped_at = datetime.now(timezone.utc)
        time.sleep(args.outage)
        peak = backlog()
        subprocess.run([*COMPOSE, "start", "kafka"], check=True, capture_output=True)
        restarted = time.monotonic()
        restarted_at = datetime.now(timezone.utc)
        loader.join()
        while backlog() > 0 and time.monotonic() - restarted < 600:
            time.sleep(0.5)
        drained = time.monotonic()
        received = wait_for_emails(recipient, set(outage_booked), 600)
        all_mail = max(received[t] for t in outage_booked if t in received)
        recovery = {
            "outage_s": round(restarted - stopped, 1), "backlog_at_restart": peak,
            "restart_to_outbox_empty_s": round(drained - restarted, 1),
            "bookings_during_outage": len(outage_booked),
            "missing_emails": len(set(outage_booked) - received.keys()),
            "last_email_after_restart_s": round(
                (all_mail - datetime.now(timezone.utc)).total_seconds() + (time.monotonic() - restarted), 1),
        }
        if args.cdc:
            # Events that occurred while the broker was down: when did each path get the last of them onto it?
            relay_times, cdc_times = topic_events(RELAY_TOPIC), topic_events(CDC_TOPIC)
            recovery["relay_last_outage_event_after_restart_s"] = last_append_after(relay_times, stopped_at,
                                                                                    restarted_at)
            recovery["cdc_last_outage_event_after_restart_s"] = last_append_after(cdc_times, stopped_at, restarted_at)
            outage_events = {e for e, t in relay_times.items() if stopped_at <= t[0] <= restarted_at}
            recovery["cdc_missing_outage_events"] = len(outage_events - cdc_times.keys())
        print(f"broker outage: {recovery}")
        results["broker_outage"] = recovery

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
