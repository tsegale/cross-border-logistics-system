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
              consumer lag, the service down), an event for an unknown shipment (dead letter), an email in UNKNOWN;
              then restored, the dead letter redriven, the UNKNOWN email resent. The delayed emails burn the
              email-delivery budget.
    phase  2  money: banking's database stopped while transfers continue (transfer budget), a payment command parked
              and retried, an approval about to expire and then approved, Kafka stopped while bookings continue
              (outbox stuck).
    phase  3  bookings: logistics' database paused while bookings continue (booking availability and latency budgets,
              gateway upstream errors), and a container filled to 94 % of its memory limit (container memory).

Slow-burn alerts (1 d and 3 d windows) keep firing for hours after any burn by design; the drill records that they
fired, and their recovery is covered by promtool tests. Needs the stack with --profile observability,
infra/observability/observability.env and benchmarks/compose.bench.yml (Mailpit's API). About 40 minutes.

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
from datetime import datetime, timezone
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
    "BookingLatencyBudgetBurnSlow", "GatewayUpstreamErrors", "ContainerMemoryNearLimit",
]
# Their windows outlast the drill: they must fire, they are not expected to resolve within it.
STAYS_FIRING = {name for name in EXPECTED if name.endswith("BurnSlow")}


def log(message: str) -> None:
    print(f"{datetime.now(timezone.utc):%H:%M:%S} {message}", flush=True)


def compose(*args: str, check: bool = True) -> str:
    return subprocess.run([*COMPOSE, *args], capture_output=True, text=True, check=check).stdout


def sql(db: str, statement: str) -> str:
    return compose("exec", "-T", f"{db}-db", "sh", "-c",
                   f'psql -U "$POSTGRES_USER" -d {db} -v ON_ERROR_STOP=1 -tAc "{statement}"').strip()


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


def publish(key: uuid.UUID, value: str) -> None:
    harness.kafka(f"echo '{key}|{value}' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:29092 "
                  "--producer.config /tmp/bench-client.properties --topic freightflow.shipment.v1 "
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


class Load(threading.Thread):
    """Bookings (and, when asked, transfers) through the gateway at a steady rate; failures are the point."""

    def __init__(self, account: str, run: str, transfers: tuple[str, str] | None = None, every: float = 1.0) -> None:
        super().__init__(daemon=True)
        self.account, self.run_id, self.transfers, self.every = account, run, transfers, every
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
                        "sourceAccount": source, "destinationAccount": target, "amount": 1.00, "currency": "EUR",
                        "reference": f"DRILL-{self.run_id}-{index}"},
                        headers={"Idempotency-Key": f"drill-transfer-{self.run_id}-{index}"}, expect=201)
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
    load = Load(account, run, every=0.5)

    ghost = uuid7()

    def middle() -> None:
        time.sleep(60)
        compose("stop", "notification-service")
        log("notification-service stopped; bookings continue")
        # A status change for a shipment notification has not seen yet: retried, then dead-lettered (ADR 0026). Its
        # shipment.booked follows later, and the operator's redrive then applies it.
        publish(ghost, event("shipment.status-changed", ghost, sequence=2, status="BOOKED"))
        log(f"published a status change for unknown shipment {ghost}")

    counts = with_load(load, 6, middle)
    log(f"bookings while broken: {counts}")
    compose("start", "notification-service", "mailpit")
    compose("up", "-d", "--wait", "notification-service", "mailpit")
    log("notification-service and Mailpit back; delayed emails now burn the email budget")
    watch.wait_fired(["ConsumerLagHigh", "EmailsFailing", "ServiceDown", "EmailDeliveryBudgetBurnFast"], 6)
    # A sender that died mid-send, simulated: an email left SENDING with its lease run out becomes UNKNOWN.
    notification = sql("notification", "UPDATE notifications SET status = 'SENDING', next_attempt_at = now() - "
                       "interval '1 minute' WHERE id = (SELECT id FROM notifications WHERE status = 'SENT' "
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
    compose("start", "banking-db")
    compose("up", "-d", "--wait", "banking-db")
    command = sql("logistics", "UPDATE shipment_payments SET status = 'PARKED', last_error = 'fire drill' WHERE id = "
                  "(SELECT id FROM shipment_payments WHERE status = 'SUCCEEDED' AND command = 'AUTHORISE' "
                  "ORDER BY created_at DESC LIMIT 1) RETURNING id")
    log(f"payment command {command} parked, as if out of attempts")
    approval = harness.call("operator", "POST", "/api/v1/approvals", {
        "kind": "DEPOSIT", "account": account, "amount": 10.00, "currency": "EUR", "reference": f"DRILL-{run}"},
        headers={"Idempotency-Key": f"drill-approval-{run}"}, expect=201)["id"]
    sql("banking", f"UPDATE approval_requests SET expires_at = now() + interval '20 minutes' WHERE id = '{approval}'")
    log(f"approval {approval} expires in 20 minutes")
    compose("stop", "kafka")
    counts = with_load(Load(account, run + "k", every=1.0), 4)
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
    log("phase 3: logistics' database paused, a container near its memory limit")
    hog = f"freightflow-drill-memory-{run}"
    subprocess.run(["docker", "run", "-d", "--rm", "--name", hog, "--memory", "64m", "alpine:3.22", "sh", "-c",
                    "head -c 60m /dev/zero | tail -c 60m > /dev/null & sleep 900"], check=True,
                   capture_output=True)
    compose("pause", "logistics-db")
    counts = with_load(Load(account, run + "p", every=0.5), 7)
    compose("unpause", "logistics-db")
    log(f"bookings while logistics' database was paused: {counts}")
    watch.wait_fired(["BookingAvailabilityBudgetBurnFast", "BookingLatencyBudgetBurnFast", "GatewayUpstreamErrors",
                      "ContainerMemoryNearLimit"], 8)
    subprocess.run(["docker", "rm", "-f", hog], capture_output=True)


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
