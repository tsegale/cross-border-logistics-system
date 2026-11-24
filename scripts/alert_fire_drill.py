#!/usr/bin/env python3
"""Alert fire drill (docs/slo.md, ADR 0033): makes every alert fire once against the running stack, checks that it
fires and, where the drill can wait for it, that it resolves, and records when.

Each alert is caused the way production would cause it, except two that are simulated in the database, said so here
and in the evidence: a parked payment command (parking needs eight failed attempts over up to 5 minutes of backoff with
the circuit breaker closed) and an email in UNKNOWN (that needs a sender killed between SMTP and the database). Their
fixes are the real ones: an operator's retry and an operator's resend, through the gateway.

    phase  0  ledger drift: an account's stored balance is changed; an operator's reconciliation finds it.
           then banking runs without its scheduled reconciliation for the rest of the drill (ReconciliationNotRunning).
    phase  1  emails and events: Mailpit and notification-service stopped while bookings continue (emails failing,
              consumer lag, the service down), a hold event for an unknown shipment (dead letter), an email in UNKNOWN;
              then restored, the dead letter redriven, the UNKNOWN email resent. The delayed emails burn the
              email-delivery budget.
    phase  2  money: banking's database stopped while transfers continue (transfer budget), a payment command parked
              and retried, an approval about to expire and then approved, Kafka stopped while bookings continue
              (outbox stuck).
    phase  3  bookings: logistics' database stopped, then paused, while bookings continue (booking availability and
              latency budgets, gateway upstream errors), and a container filled to 92 % of its memory limit (container memory).
    phase  4  reference rates (ADR 0034): the workflow starts the partner simulator three working days behind and the
              Bank of Namibia 5 % off the NAD/ZAR peg, so banking's first rate is quarantined and there is no usable one
              from the start (stale, quarantined). The drill puts both sources right, waits for a fresh confirmed rate,
              and has one operator request and a second reject each quarantined rate (four eyes, ADR 0021).

Burn-rate alerts keep firing after the burn by design (a fast burn up to 30 minutes, a slow one for hours); the drill
records that they fired, and their recovery is covered by promtool tests. Needs the stack with --profile observability,
infra/observability/observability.env and benchmarks/compose.bench.yml (Mailpit's API), and the partner simulator
started lagging and off the peg (SIMULATOR_* in .github/workflows/fire-drill.yml). About 45 minutes.

    python3 scripts/alert_fire_drill.py --json drill.json
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.parse
import urllib.request
import uuid
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "benchmarks"))
import events_to_email as harness  # noqa: E402

PROMETHEUS = "http://127.0.0.1:9090"
MAILPIT = "http://127.0.0.1:8025"
COMPOSE = os.environ.get("COMPOSE", "docker compose").split()

EXPECTED = [
    "LedgerDrift", "ReconciliationNotRunning", "EmailsFailing", "ConsumerLagHigh", "ServiceDown", "DeadLettersWaiting",
    "EmailsUnknown", "EmailDeliveryBudgetBurnFast", "EmailDeliveryBudgetBurnSlow", "TransferAvailabilityBudgetBurnFast",
    "TransferAvailabilityBudgetBurnSlow", "PaymentCommandsParked", "ApprovalsNearExpiry", "OutboxStuck",
    "BookingAvailabilityBudgetBurnFast", "BookingAvailabilityBudgetBurnSlow", "BookingLatencyBudgetBurnFast",
    "BookingLatencyBudgetBurnSlow", "GatewayUpstreamErrors", "ContainerMemoryNearLimit", "FxReferenceRateStale",
    "FxReferenceRateQuarantined",
]
# Burn-rate alerts outlast the drill: a fast burn keeps firing up to 30 minutes after the outage (its 6 h and 30 m
# pair), a slow burn for hours. They must fire here; that they clear is proved by the promtool recovery tests.
STAYS_FIRING = {name for name in EXPECTED if "BudgetBurn" in name}


def log(message: str) -> None:
    print(f"{datetime.now(timezone.utc):%H:%M:%S} {message}", flush=True)


def compose(*args: str, check: bool = True) -> str:
    return subprocess.run([*COMPOSE, *args], capture_output=True, text=True, check=check).stdout


def sql(db: str, statement: str) -> str:
    return compose("exec", "-T", f"{db}-db", "sh", "-c",
                   f'psql -U "$POSTGRES_USER" -d {db} -v ON_ERROR_STOP=1 -qtAc "{statement}"').strip()


def get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.loads(response.read() or b"{}")


def uuid7() -> uuid.UUID:
    """A UUIDv7 (RFC 9562), as the producers make event ids: notification reads the write time from it."""
    ms = int(time.time() * 1000)
    rand = int.from_bytes(os.urandom(10), "big")
    value = (ms << 80) | (0x7 << 76) | ((rand >> 64) & 0x0FFF) << 64 | (0b10 << 62) | (rand & ((1 << 62) - 1))
    return uuid.UUID(int=value)


def event(kind: str, shipment: uuid.UUID, sequence: int, status: str) -> str:
    """The schema's example of the event (docs/events), for this shipment, as logistics would write it."""
    schema = Path(__file__).resolve().parent.parent / "docs" / "events" / f"{kind}.schema.json"
    body = json.loads(schema.read_text(encoding="utf-8"))["examples"][0]
    body.update(eventId=str(uuid7()), aggregateId=str(shipment), sequence=sequence,
                occurredAt=datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"))
    body["payload"].update(shipmentId=str(shipment), organizationId=harness.HANSEATIC,
                           payerOrganizationId=harness.HANSEATIC, status=status)
    return json.dumps(body, separators=(",", ":"))


def hold_released(shipment: uuid.UUID) -> tuple[uuid.UUID, str]:
    """banking's hold.released for a hold taken for this shipment (its reference names the shipment)."""
    schema = Path(__file__).resolve().parent.parent / "docs" / "events" / "hold.released.schema.json"
    body = json.loads(schema.read_text(encoding="utf-8"))["examples"][0]
    hold = uuid7()
    body.update(eventId=str(uuid7()), aggregateId=str(hold), sequence=2,
                occurredAt=datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z"))
    body["payload"]["hold"].update(holdId=str(hold), organizationId=harness.HANSEATIC, reference=f"SHIPMENT {shipment}")
    return hold, json.dumps(body, separators=(",", ":"))


def publish(key: uuid.UUID, value: str, topic: str = "freightflow.shipment.v1") -> None:
    harness.kafka(f"echo '{key}|{value}' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 "
                  f"--producer.config /tmp/bench-client.properties --topic {topic} "
                  "--property parse.key=true --property key.separator='|'")


class Watch(threading.Thread):
    """Polls Prometheus' alerts every 10 s and records when each first fired and when it was last resolved."""

    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.fired: dict[str, str] = {}
        self.resolved: dict[str, str] = {}
        self.firing: set[str] = set()
        self.stop = threading.Event()

    def run(self) -> None:
        while not self.stop.is_set():
            try:
                alerts = get(f"{PROMETHEUS}/api/v1/alerts")["data"]["alerts"]
                now = {a["labels"]["alertname"] for a in alerts if a["state"] == "firing"}
                stamp = datetime.now(timezone.utc).isoformat(timespec="seconds")
                for name in now - self.firing:
                    self.fired.setdefault(name, stamp)
                    log(f"FIRING   {name}")
                for name in self.firing - now:
                    self.resolved[name] = stamp
                    log(f"resolved {name}")
                self.firing = now
            except OSError as error:
                log(f"watch: {error}")
            self.stop.wait(10)

    def wait_fired(self, names: list[str], minutes: float) -> None:
        deadline = time.monotonic() + minutes * 60
        while time.monotonic() < deadline and not all(n in self.fired for n in names):
            time.sleep(5)

    def wait_resolved(self, names: list[str], minutes: float) -> None:
        deadline = time.monotonic() + minutes * 60
        while time.monotonic() < deadline and any(n in self.firing for n in names):
            time.sleep(5)


def book_quoted(quote: str, account: str, run: str, index: int) -> None:
    pickup = (datetime.now(timezone.utc).date() + timedelta(days=7)).isoformat()
    harness.call("customer-hanseatic-imports", "POST", "/api/v1/shipments", {
        "quoteId": quote,
        "shipper": {"name": "Namib Fisheries", "address": "Walvis Bay", "contact": "x"},
        "consignee": {"name": "Hanseatic Seafood", "address": "Hamburg", "contact": "y"},
        "payerAccountNumber": account, "goodsDescription": "Frozen hake", "declaredValue": 18500.00,
        "declaredValueCurrency": "EUR", "plannedPickupDate": pickup,
    }, headers={"Idempotency-Key": f"drill-quoted-{run}-{index}"}, expect=201)


class Load(threading.Thread):
    """Bookings (and, when asked, transfers) through the gateway at a steady rate; failures are the point."""

    def __init__(self, account: str, run: str, transfers: tuple[str, str] | None = None, every: float = 1.0,
                 quotes: list[str] | None = None) -> None:
        super().__init__(daemon=True)
        self.account, self.run_id, self.transfers, self.every = account, run, transfers, every
        self.quotes = quotes
        self.stop = threading.Event()
        self.counts = {"ok": 0, "failed": 0}

    def run(self) -> None:
        index = 0
        while not self.stop.is_set():
            index += 1
            try:
                if self.transfers:
                    source, target = self.transfers
                    harness.call("customer-hanseatic-imports", "POST", "/api/v1/transfers", {
                        "sourceAccount": source, "targetAccount": target, "amount": 1.00, "currency": "EUR",
                        "reference": f"DRILL-{self.run_id}-{index}"},
                        headers={"Idempotency-Key": f"drill-transfer-{self.run_id}-{index}"}, expect=201)
                elif self.quotes:
                    # Quoted before the outage: the booking itself is what reaches logistics.
                    book_quoted(self.quotes[(index - 1) % len(self.quotes)], self.account, self.run_id, index)
                else:
                    harness.book(self.account, f"drill-{self.run_id}", index)
                self.counts["ok"] += 1
            except Exception:  # noqa: BLE001 - errors are what the drill causes
                self.counts["failed"] += 1
            self.stop.wait(self.every)


def with_load(load: Load, minutes: float, during=None) -> dict:
    load.start()
    try:
        if during:
            during()
        time.sleep(minutes * 60)
    finally:
        load.stop.set()
        load.join(timeout=60)
    return load.counts


def phase0_ledger(watch: Watch, account: str) -> None:
    log("phase 0: ledger drift")
    account_id = sql("banking", f"SELECT id FROM accounts WHERE account_number = '{account}'")
    sql("banking", f"UPDATE accounts SET balance = balance + 1.0000 WHERE id = '{account_id}'")
    harness.call("operator", "GET", "/api/v1/ledger/reconciliation")
    watch.wait_fired(["LedgerDrift"], 2)
    sql("banking", f"UPDATE accounts SET balance = balance - 1.0000 WHERE id = '{account_id}'")
    report = harness.call("operator", "GET", "/api/v1/ledger/reconciliation")
    log(f"reconciled again: balanced={report.get('balanced')}")
    watch.wait_resolved(["LedgerDrift"], 2)
    # From now on banking runs without its scheduled check: the age climbs until ReconciliationNotRunning fires.
    subprocess.run([*COMPOSE, "up", "-d", "--wait", "--no-deps", "banking-service"],
                   env={**os.environ, "FREIGHTFLOW_BANKING_RECONCILIATION_ENABLED": "false"}, check=True)
    log("banking-service recreated without its scheduled reconciliation")


def phase1_emails(watch: Watch, account: str, run: str) -> None:
    log("phase 1: emails, consumer lag, dead letters, UNKNOWN")
    compose("stop", "mailpit")
    # A quiet background (one booking every 4 s): the booking SLOs' burn in phase 3 is measured against this hour.
    load = Load(account, run, every=4.0)

    ghost = uuid7()

    def middle() -> None:
        # Long enough for the emails Mailpit refuses to use up their six attempts (about a minute of backoff) first.
        time.sleep(150)
        compose("stop", "notification-service")
        log("notification-service stopped; bookings continue")
        # A hold released for a shipment notification has not seen yet: retried on the retry topics, then
        # dead-lettered (ADR 0026). The shipment's booking event follows later, and the operator's redrive applies it.
        hold, released = hold_released(ghost)
        publish(hold, released, topic="freightflow.hold.v1")
        log(f"published hold.released for unknown shipment {ghost}")

    counts = with_load(load, 8, middle)
    log(f"bookings while broken: {counts}")
    compose("start", "notification-service", "mailpit")
    compose("up", "-d", "--wait", "notification-service", "mailpit")
    log("notification-service and Mailpit back; delayed emails now burn the email budget")
    watch.wait_fired(["ConsumerLagHigh", "EmailsFailing", "ServiceDown", "EmailDeliveryBudgetBurnFast"], 6)
    # A sender that died mid-send, simulated: an email left SENDING with its lease run out becomes UNKNOWN.
    notification = sql("notification", "UPDATE notifications SET status = 'SENDING', sent_at = NULL, next_attempt_at = "
                       "now() - interval '1 minute' WHERE id = (SELECT id FROM notifications WHERE status = 'SENT' "
                       "ORDER BY created_at DESC LIMIT 1) RETURNING id")
    log(f"email {notification} left SENDING, as if its sender died")
    watch.wait_fired(["EmailsUnknown", "DeadLettersWaiting"], 8)
    harness.call("operator", "POST", f"/api/v1/notifications/{notification}/resend", {"acknowledgeDuplicate": True})
    log("UNKNOWN email resent by an operator")
    publish(ghost, event("shipment.booked", ghost, sequence=1, status="AWAITING_PAYMENT"))
    time.sleep(10)
    for letter in harness.call("operator", "GET", "/api/v1/dead-letters?status=NEW&limit=200")["items"]:
        harness.call("operator", "POST", f"/api/v1/dead-letters/{letter['id']}/redrive")
        log(f"dead letter {letter['id']} redriven by an operator")
    # EmailsFailing resolves once no email was refused for good in the last hour: the drill waits for that at the end.


def phase2_money(watch: Watch, account: str, run: str, second: str) -> None:
    log("phase 2: transfers, a parked command, an approval about to expire, Kafka stopped")
    compose("stop", "banking-db")
    counts = with_load(Load(account, run, transfers=(account, second), every=0.5), 4)
    log(f"transfers while banking's database was down: {counts}")
    # A booking whose hold banking cannot authorise now: its AUTHORISE fails with 503s. Parked here as if it had run
    # out of attempts; the operator's retry below, once banking is back, pays for it for real.
    tracking, _ = harness.book(account, f"drill-park-{run}", 1)
    command = sql("logistics", "UPDATE shipment_payments SET status = 'PARKED', last_error = 'fire drill' WHERE id = "
                  "(SELECT p.id FROM shipment_payments p JOIN shipments s ON s.id = p.shipment_id WHERE "
                  f"s.tracking_number = '{tracking}' AND p.command = 'AUTHORISE') RETURNING id")
    log(f"payment command {command} of {tracking} parked, as if out of attempts")
    compose("start", "banking-db")
    compose("up", "-d", "--wait", "banking-db")
    approval = harness.call("operator", "POST", "/api/v1/approvals", {
        "kind": "DEPOSIT", "account": account, "amount": 10.00, "currency": "EUR", "reference": f"DRILL-{run}"},
        headers={"Idempotency-Key": f"drill-approval-{run}"}, expect=201)["id"]
    sql("banking", f"UPDATE approval_requests SET expires_at = now() + interval '20 minutes' WHERE id = '{approval}'")
    log(f"approval {approval} expires in 20 minutes")
    compose("stop", "kafka")
    counts = with_load(Load(account, run + "k", every=4.0), 4)
    log(f"bookings while Kafka was down: {counts}")
    compose("start", "kafka")
    watch.wait_fired(["TransferAvailabilityBudgetBurnFast", "PaymentCommandsParked", "ApprovalsNearExpiry",
                      "OutboxStuck"], 6)
    # Kafka's restart needs kafka-init only on a new cluster; the users and ACLs are in its metadata log.
    harness.call("operator", "POST", f"/api/v1/payment-commands/{command}/retry")
    harness.call("second-operator", "POST", f"/api/v1/approvals/{approval}/approval", {"comment": "fire drill"},
                 headers={"Idempotency-Key": f"drill-approve-{run}"})
    log("parked command retried, approval approved")


def phase3_bookings(watch: Watch, account: str, run: str) -> None:
    log("phase 3: logistics' database stopped then paused, a container near its memory limit")
    hog = f"freightflow-drill-memory-{run}"
    # awk holding a 60 MB string in a 64 MiB container: 92 % of its limit, measured on Docker 29.
    subprocess.run(["docker", "run", "-d", "--rm", "--name", hog, "--memory", "64m", "alpine:3.22", "awk",
                    'BEGIN { s = sprintf("%60000000s", ""); system("sleep 1200") }'], check=True, capture_output=True)
    # Stopped: bookings fail with 503 within the connection timeout (availability). Paused: they wait on the database
    # and finish late once it is back (latency).
    # Quotes first: with the database down a quote fails too, and the booking (the SLI) would never be asked for.
    quotes = [harness.call("customer-hanseatic-imports", "POST", "/api/v1/quotes", harness.QUOTE, expect=201)["id"]
              for _ in range(40)]
    compose("stop", "logistics-db")
    counts = with_load(Load(account, run + "s", every=0.5, quotes=quotes), 7)
    compose("start", "logistics-db")
    compose("up", "-d", "--wait", "logistics-db")
    log(f"bookings while logistics' database was stopped: {counts}")
    compose("pause", "logistics-db")
    counts = with_load(Load(account, run + "p", every=0.5, quotes=quotes), 4)
    compose("unpause", "logistics-db")
    log(f"bookings while logistics' database was paused: {counts}")
    watch.wait_fired(["BookingAvailabilityBudgetBurnFast", "BookingLatencyBudgetBurnFast", "GatewayUpstreamErrors",
                      "ContainerMemoryNearLimit"], 8)
    subprocess.run(["docker", "rm", "-f", hog], capture_output=True)


def simulator(settings: dict) -> None:
    """Changes the partner simulator's behaviour through its control endpoint, inside the compose network."""
    compose("exec", "-T", "partner-simulator", "python", "-c",
            "import sys, urllib.request; urllib.request.urlopen(urllib.request.Request("
            "'http://127.0.0.1:8090/_control', data=sys.argv[1].encode(), method='POST'), timeout=5)",
            json.dumps(settings))


def phase4_rates(watch: Watch, run: str) -> None:
    log("phase 4: reference rates three working days old and off the peg since the start")
    watch.wait_fired(["FxReferenceRateStale", "FxReferenceRateQuarantined"], 8)
    simulator({"ecbLagWorkingDays": 0, "bonFactor": "1.0016"})
    # banking checks every minute in the drill (FX_REFRESH_INTERVAL); a fresh day is backed by the Bank of Namibia.
    deadline = time.monotonic() + 5 * 60
    while time.monotonic() < deadline:
        confirmed = harness.call("operator", "GET", "/api/v1/reference-rates?status=CONFIRMED&limit=1")["items"]
        if confirmed:
            log(f"reference rate for {confirmed[0]['valueDate']} confirmed")
            break
        time.sleep(10)
    quarantined = harness.call("operator", "GET", "/api/v1/reference-rates?status=QUARANTINED&limit=100")["items"]
    for index, rate in enumerate(quarantined):
        approval = harness.call("operator", "POST", "/api/v1/approvals", {
            "kind": "RATE_CONFIRMATION", "referenceRateId": rate["id"], "reason": "fire drill: checked, wrong"},
            headers={"Idempotency-Key": f"drill-rate-{run}-{index}"}, expect=201)["id"]
        harness.call("second-operator", "POST", f"/api/v1/approvals/{approval}/rejection",
                     {"comment": "fire drill: the simulator was lagging and off the peg"})
    log(f"rejected {len(quarantined)} quarantined reference rate(s) with four eyes")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", help="write the evidence here")
    args = parser.parse_args()

    run = str(int(time.time()))
    _, account = harness.setup(run)
    second = harness.call("operator", "POST", "/api/v1/accounts", {"currency": "EUR",
                                                                    "organizationId": harness.HANSEATIC},
                          expect=201)["accountNumber"]
    watch = Watch()
    watch.start()
    started = datetime.now(timezone.utc).isoformat(timespec="seconds")
    try:
        phase0_ledger(watch, account)
        phase1_emails(watch, account, run)
        phase2_money(watch, account, run, second)
        phase3_bookings(watch, account, run)
        phase4_rates(watch, run)
        watch.wait_fired(["ReconciliationNotRunning"], 15)
        compose("up", "-d", "--wait", "--no-deps", "banking-service")
        log("banking-service back with its scheduled reconciliation")
        watch.wait_resolved([n for n in EXPECTED if n not in STAYS_FIRING], 12)
    finally:
        watch.stop.set()
        watch.join(timeout=30)

    emails = get(f"{MAILPIT}/api/v1/search?" + urllib.parse.urlencode({"query": "to:on-call@freightflow.dev"}))
    evidence = {
        "started": started, "finished": datetime.now(timezone.utc).isoformat(timespec="seconds"),
        "alerts": {name: {"fired": watch.fired.get(name), "resolved": watch.resolved.get(name),
                          "still_firing": name in watch.firing} for name in EXPECTED},
        "alert_emails": emails.get("messages_count", emails.get("total")),
    }
    never = [n for n in EXPECTED if n not in watch.fired]
    stuck = [n for n in EXPECTED if n not in STAYS_FIRING and n in watch.firing]
    evidence["never_fired"], evidence["did_not_resolve"] = never, stuck
    if args.json:
        Path(args.json).write_text(json.dumps(evidence, indent=2) + "\n")
    print(json.dumps(evidence, indent=2))
    if never or stuck:
        raise SystemExit(f"fire drill: never fired {never}, did not resolve {stuck}")


if __name__ == "__main__":
    main()
