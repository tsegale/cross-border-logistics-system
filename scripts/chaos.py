"""Chaos experiments against the running stack (docs/chaos, ADR 0045).

Each experiment states a hypothesis, measures the steady state (a customer booking a shipment every second through
the gateway), applies one fault, removes it, and checks:
- how many bookings the fault cost, and how long the service took to come back;
- that every booking the gateway accepted settled: booked and paid, or refused with a reason, never stuck;
- the invariant auditor's verdict (scripts/audit_invariants.py).

    docker compose -f docker-compose.yml -f infra/chaos/compose.chaos.yml up -d --wait
    python scripts/chaos.py kill-banking --json chaos/kill-banking.json
    python scripts/chaos.py all --out chaos

Experiments: kill-banking, kill-kafka, partition-banking, slow-banking-db, keycloak-down, redis-down, clock-skew,
certificate-expiry. Needs the chaos overlay for partition-banking and slow-banking-db.
"""
from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPOSITORY / "benchmarks"))
sys.path.insert(0, str(REPOSITORY / "scripts"))
import events_to_email as harness  # noqa: E402
from internal_ca import ssl_context  # noqa: E402

COMPOSE = ["docker", "compose", "--project-directory", str(REPOSITORY),
           "-f", str(REPOSITORY / "docker-compose.yml"), "-f", str(REPOSITORY / "infra/chaos/compose.chaos.yml")]
BASELINE_SECONDS = 30
RECOVERED_AFTER = 5   # consecutive successful bookings
RECOVERY_TIMEOUT = 600
SETTLE_TIMEOUT = 420


def log(message: str) -> None:
    print(f"{datetime.now(timezone.utc):%H:%M:%S} {message}", flush=True)


def compose(*args: str, env: dict | None = None, check: bool = True) -> str:
    result = subprocess.run([*COMPOSE, *args], capture_output=True, text=True, timeout=900,
                            env={**os.environ, **(env or {})})
    if check and result.returncode != 0:
        raise RuntimeError(f"compose {' '.join(args)}: {result.stderr.strip()[-500:]}")
    return result.stdout


def toxiproxy(*args: str) -> str:
    return compose("exec", "-T", "toxiproxy", "/toxiproxy-cli", *args)


@dataclass
class Attempt:
    at: float
    ok: bool
    seconds: float
    tracking: str | None = None
    error: str | None = None


@dataclass(eq=False)  # a thread is kept in a WeakSet, so it must stay hashable
class Traffic(threading.Thread):
    """A customer booking a shipment every second, as the steady state."""
    account: str
    # Not "run": a field of that name would replace Thread.run, and the thread would never book anything.
    tag: str
    attempts: list[Attempt] = field(default_factory=list)

    def __post_init__(self) -> None:
        super().__init__(daemon=True)
        self.halt = threading.Event()

    def run(self) -> None:  # noqa: D401 threading's own method
        number = 0
        while not self.halt.is_set():
            number += 1
            started = time.monotonic()
            try:
                tracking, _ = harness.book(self.account, f"chaos-{self.tag}", number)
                self.attempts.append(Attempt(time.time(), True, time.monotonic() - started, tracking))
            except Exception as error:  # noqa: BLE001 every failure is counted with its reason
                reason = f"{error.code} {error.reason}" if isinstance(error, urllib.error.HTTPError) else type(error).__name__
                harness.TOKENS.forget("customer-hanseatic-imports")
                self.attempts.append(Attempt(time.time(), False, time.monotonic() - started, error=reason))
            self.halt.wait(max(0.0, 1.0 - (time.monotonic() - started)))

    def window(self, start: float, end: float) -> dict:
        inside = [a for a in self.attempts if start <= a.at < end]
        ok = [a for a in inside if a.ok]
        seconds = sorted(a.seconds for a in ok)
        errors: dict[str, int] = {}
        for attempt in inside:
            if not attempt.ok:
                errors[attempt.error] = errors.get(attempt.error, 0) + 1
        return {"attempts": len(inside), "succeeded": len(ok),
                "success_rate": round(len(ok) / len(inside), 3) if inside else None,
                "p95_seconds": round(harness.percentile(seconds, 95), 3) if seconds else None,
                "errors": errors}

    def recovered_at(self, after: float) -> float | None:
        streak = 0
        for attempt in self.attempts:
            if attempt.at < after:
                continue
            streak = streak + 1 if attempt.ok else 0
            if streak == RECOVERED_AFTER:
                return attempt.at
        return None


# ---- the faults: each returns a note, and leaves the stack whole when it returns ----

def kill_banking(seconds: int = 30) -> str:
    compose("kill", "-s", "SIGKILL", "banking-service")
    time.sleep(seconds)
    compose("up", "-d", "--wait", "--no-deps", "banking-service")
    return f"banking-service killed (SIGKILL) for {seconds} s, then started"


def kill_kafka(seconds: int = 30, account: str = "", run: str = "") -> str:
    # A burst of bookings while the broker is gone: 40 at 4 per second, beside the steady customer.
    burst = threading.Thread(target=lambda: harness.steady(account, f"burst-{run}", 4.0, 10, 100_000), daemon=True)
    compose("kill", "-s", "SIGKILL", "kafka")
    burst.start()
    time.sleep(seconds)
    compose("up", "-d", "--wait", "--no-deps", "kafka")
    burst.join(timeout=120)
    return f"kafka killed (SIGKILL) for {seconds} s during a burst of 40 bookings, then started"


def partition_banking(seconds: int = 60) -> str:
    toxiproxy("toggle", "banking")
    time.sleep(seconds)
    toxiproxy("toggle", "banking")
    return f"logistics cut off from banking for {seconds} s (Toxiproxy), banking up for everyone else"


def slow_banking_db(seconds: int = 60, latency_ms: int = 300) -> str:
    toxiproxy("toxic", "add", "-n", "slow", "-t", "latency", "-a", f"latency={latency_ms}", "banking-db")
    time.sleep(seconds)
    toxiproxy("toxic", "remove", "-n", "slow", "banking-db")
    return f"{latency_ms} ms added to every packet between banking and its database for {seconds} s"


def keycloak_down(seconds: int = 420) -> str:
    compose("stop", "keycloak")
    time.sleep(seconds)
    compose("up", "-d", "--wait", "--no-deps", "keycloak")
    return f"Keycloak stopped for {seconds} s, past the 300 s access token lifetime"


def redis_down(seconds: int = 60) -> str:
    compose("stop", "redis")
    time.sleep(seconds)
    compose("up", "-d", "--wait", "--no-deps", "redis")
    return f"Redis stopped for {seconds} s"


def clock_skew(seconds: int = 90, offset: str = "120s") -> str:
    compose("up", "-d", "--wait", "--no-deps", "banking-service", env={"BANKING_CLOCK_OFFSET": offset})
    time.sleep(seconds)
    compose("up", "-d", "--wait", "--no-deps", "banking-service", env={"BANKING_CLOCK_OFFSET": "0s"})
    return f"banking-service's clock {offset} ahead for {seconds} s (beyond its 30 s token tolerance), then restored"


def certificate_expiry(lifetime: str = "3m") -> str:
    compose("exec", "-T", "-e", f"CERT_LIFETIME={lifetime}", "cert-agent", "sh", "/etc/freightflow/tls/agent.sh",
            "issue", "banking-service")
    compose("stop", "ca")
    # Past its expiry, with no CA to renew it: callers must refuse the expired certificate.
    time.sleep(240)
    compose("up", "-d", "--wait", "--no-deps", "ca")
    compose("exec", "-T", "cert-agent", "sh", "/etc/freightflow/tls/agent.sh", "renew-all")
    return f"banking-service given a {lifetime} certificate, the CA stopped until it had expired, then started"


EXPERIMENTS = {
    "kill-banking": (kill_banking, "Bookings keep being accepted while banking is gone; their holds are made once "
                                   "banking is back, none twice, and the ledger reconciles."),
    "kill-kafka": (kill_kafka, "Bookings keep being accepted with the broker gone; events wait in the outboxes and "
                               "are published once it is back, in order per aggregate."),
    "partition-banking": (partition_banking, "With logistics cut off from banking, bookings are accepted and wait "
                                             "for payment; after the partition every one is paid once."),
    "slow-banking-db": (slow_banking_db, "A slow banking database slows payments but loses none, and bookings at "
                                         "the edge are not refused for it."),
    "keycloak-down": (keycloak_down, "Without Keycloak no new token can be had: requests fail with 401 once tokens "
                                     "expire, nothing half-done remains, and everything resumes when it is back."),
    "redis-down": (redis_down, "Without Redis the money routes, bookings included, fail closed with 503: no request "
                               "goes unbudgeted, and nothing else is affected."),
    "clock-skew": (clock_skew, "A banking clock beyond the token tolerance refuses tokens (401), so payments wait; "
                               "no money moves on a wrong time, and all settle when the clock is right."),
    "certificate-expiry": (certificate_expiry, "An expired certificate is refused by every caller, the alert fires, "
                                               "and renewal after the CA returns restores the service."),
}


def settle(bookings: list[str]) -> dict:
    """Waits until every accepted booking is booked or refused for a reason; returns the statuses."""
    deadline = time.monotonic() + SETTLE_TIMEOUT
    statuses: dict[str, str] = {}
    while True:
        for tracking in bookings:
            if statuses.get(tracking) in (None, "AWAITING_PAYMENT"):
                try:
                    statuses[tracking] = harness.call("customer-hanseatic-imports", "GET",
                                                      f"/api/v1/shipments/track/{tracking}")["status"]
                except Exception:  # noqa: BLE001 asked again on the next round
                    harness.TOKENS.forget("customer-hanseatic-imports")
        waiting = [t for t in bookings if statuses.get(t) in (None, "AWAITING_PAYMENT")]
        if not waiting or time.monotonic() > deadline:
            break
        time.sleep(10)
    counts: dict[str, int] = {}
    for status in statuses.values():
        counts[status] = counts.get(status, 0) + 1
    return {"statuses": counts, "unsettled": [t for t in bookings if statuses.get(t) in (None, "AWAITING_PAYMENT")]}


def audit() -> dict:
    out = REPOSITORY / "chaos-audit.json"
    result = subprocess.run([sys.executable, str(REPOSITORY / "scripts" / "audit_invariants.py"), "--json", str(out)],
                            capture_output=True, text=True, timeout=900)
    print(result.stdout[-3000:], flush=True)
    verdict = json.loads(out.read_text()) if out.exists() else {"holds": False, "error": result.stderr[-500:]}
    out.unlink(missing_ok=True)
    return verdict


def experiment(name: str, account: str, run: str) -> dict:
    fault, hypothesis = EXPERIMENTS[name]
    log(f"{name}: {hypothesis}")
    traffic = Traffic(account, f"{run}-{name}")
    traffic.start()
    time.sleep(BASELINE_SECONDS)
    fault_start = time.time()
    note = fault(account=account, run=run) if name == "kill-kafka" else fault()
    fault_end = time.time()
    log(f"{name}: {note}")
    deadline = time.time() + RECOVERY_TIMEOUT
    while traffic.recovered_at(fault_end) is None and time.time() < deadline:
        time.sleep(5)
    recovered = traffic.recovered_at(fault_end)
    time.sleep(10)
    traffic.halt.set()
    traffic.join(timeout=60)
    end = time.time()
    booked = [a.tracking for a in traffic.attempts if a.ok]
    settled = settle(booked)
    verdict = audit()
    result = {
        "experiment": name, "hypothesis": hypothesis, "fault": note,
        "steady_state": traffic.window(fault_start - BASELINE_SECONDS, fault_start),
        "during_fault": traffic.window(fault_start, fault_end),
        "after_fault": traffic.window(fault_end, end),
        "fault_seconds": round(fault_end - fault_start),
        "recovered_seconds_after_fault": round(recovered - fault_end) if recovered else None,
        "settled": settled,
        "invariants_hold": verdict.get("holds"),
        "audit": verdict,
    }
    log(f"{name}: recovered after {result['recovered_seconds_after_fault']} s; settled {settled['statuses']}; "
        f"invariants {'hold' if verdict.get('holds') else 'BROKEN'}")
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("experiment", choices=[*EXPERIMENTS, "all"])
    parser.add_argument("--json", help="one experiment's result")
    parser.add_argument("--out", default="chaos", help="with all: a directory, one file per experiment")
    args = parser.parse_args()
    run = str(int(time.time()))
    _, account = harness.setup(run)
    names = list(EXPERIMENTS) if args.experiment == "all" else [args.experiment]
    failed = []
    for name in names:
        result = experiment(name, account, run)
        target = Path(args.json) if args.json and len(names) == 1 else Path(args.out) / f"{name}.json"
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(result, indent=2))
        if not result["invariants_hold"] or result["settled"]["unsettled"]:
            failed.append(name)
    log("every experiment ended with the invariants holding and every booking settled" if not failed
        else f"failed: {failed}")
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
