#!/usr/bin/env python3
"""Events benchmark (ADR 0025, ADR 0027): booking to email delivered, outbox lag, and recovery after a broker outage.

Books shipments through the gateway as Hanseatic Imports (German emails) at a steady rate and measures, per booking,
the time from the booking's 201 to Mailpit receiving its "Sendung gebucht" email (both clocks are the runner's).
Reads the outbox relays' lag (commit to broker acknowledgement) from banking and logistics. Then keeps booking while
Kafka is stopped for --outage seconds, and measures from the broker's restart until both outboxes are empty and
every email of the outage has arrived.

Needs the stack with benchmarks/compose.bench.yml (it publishes Mailpit, logistics and banking's actuators on
127.0.0.1) and docker compose on the PATH. Standard library only.

    python3 benchmarks/events_to_email.py --rate 2 --seconds 120 --outage 30 --json out.json
"""
from __future__ import annotations

import argparse
import json
import os
import statistics
import subprocess
import sys
import threading
import time
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
    call("customer-hanseatic-imports", "PUT", "/api/v1/notification-settings", {
        "language": "de", "recipients": [recipient],
        "kinds": ["shipment-booked", "shipment-status", "payment-failed", "payment-captured", "payment-released"]})
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


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rate", type=float, default=2.0, help="bookings per second")
    parser.add_argument("--seconds", type=float, default=120, help="length of the steady phase")
    parser.add_argument("--outage", type=float, default=30, help="seconds Kafka is stopped; 0 skips the outage")
    parser.add_argument("--json", help="write the results here")
    args = parser.parse_args()
    run = str(int(time.time()))

    recipient, account = setup(run)
    # Warm-up, discarded: the first bookings after a start measure the JIT.
    warm = steady(account, run, args.rate, 10, 0)
    wait_for_emails(recipient, set(warm), 120)

    before = {service: lag_buckets(service) for service in ACTUATORS}
    booked = steady(account, run, args.rate, args.seconds, 10_000)
    received = wait_for_emails(recipient, set(booked), 120)
    lag = outbox_lag(before, {service: lag_buckets(service) for service in ACTUATORS})
    steady_result = summary(latencies(booked, received))
    steady_result["missing"] = len(set(booked) - received.keys())
    print(f"steady {args.rate}/s for {args.seconds:.0f} s: booking to email {steady_result}")
    print(f"outbox lag (histogram buckets, commit to broker ack): {lag}")
    results = {"rate_per_s": args.rate, "seconds": args.seconds, "booking_to_email": steady_result,
               "outbox_lag": lag}

    if args.outage > 0:
        outage_booked: dict[str, datetime] = {}
        loader = threading.Thread(target=lambda: outage_booked.update(
            steady(account, run, args.rate, args.outage + 10, 20_000)))
        loader.start()
        time.sleep(5)
        subprocess.run([*COMPOSE, "stop", "kafka"], check=True, capture_output=True)
        stopped = time.monotonic()
        time.sleep(args.outage)
        peak = backlog()
        subprocess.run([*COMPOSE, "start", "kafka"], check=True, capture_output=True)
        restarted = time.monotonic()
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
        print(f"broker outage: {recovery}")
        results["broker_outage"] = recovery

    if args.json:
        Path(args.json).write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
