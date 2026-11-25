#!/usr/bin/env python3
"""Every certificate renewed under traffic, and nothing refused (ADR 0039, ADR 0040).

Customers keep booking shipments through the gateway, one a second, each one crossing every TLS hop: the gateway,
Keycloak's keys, logistics and banking over mutual TLS, both databases, the broker, notification and Mailpit. Twenty
seconds in, the agent renews every certificate at once (agent.sh renew-all). Every endpoint must then serve its new
certificate within three minutes, without a restart, while no request fails and every booking's email arrives.

Keycloak re-reads its certificate every hour (KC_HTTPS_CERTIFICATE_RELOAD_PERIOD), so it is held only to keep
answering; its old certificate stays valid for 24 h.

    python scripts/rotation_test.py --json rotation.json
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import threading
import time
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPOSITORY / "benchmarks"))
import events_to_email as harness  # noqa: E402

COMPOSE = ["docker", "compose", "--project-directory", str(REPOSITORY)]
SLOW_RELOAD = {"keycloak:8443", "keycloak:9000"}


def served() -> dict[str, str]:
    """The serial each endpoint presents now, read by scripts/tls_probe.py from inside the compose network."""
    result = subprocess.run(
        ["docker", "run", "--rm", "--network", "freightflow_default", "-v", "freightflow_certs:/certs:ro",
         "-v", f"{REPOSITORY / 'scripts'}:/probe:ro", "python:3.15.2-alpine", "python", "-I", "/probe/tls_probe.py",
         "--serials"], capture_output=True, text=True, check=True, timeout=120)
    return json.loads(result.stdout)


def booking_emails(recipient: str, booked: set[str], timeout: float) -> set[str]:
    """The tracking numbers whose booking email reached Mailpit, asked from inside the network (no port published)."""
    deadline = time.monotonic() + timeout
    while True:
        query = f"to%3A%22{recipient}%22"
        result = subprocess.run([*COMPOSE, "exec", "-T", "notification-service", "curl", "-fsS", "--cacert",
                                 "/tls/ca.pem", f"https://mailpit:8025/api/v1/search?query={query}&limit=1000"],
                                capture_output=True, text=True, timeout=30)
        subjects = [m["Subject"] for m in json.loads(result.stdout or "{}").get("messages", [])]
        arrived = {tracking for tracking in booked if any(tracking in subject for subject in subjects)}
        if arrived == booked or time.monotonic() > deadline:
            return arrived
        time.sleep(5)


class Traffic(threading.Thread):
    def __init__(self, account: str, run: str) -> None:
        super().__init__(daemon=True)
        self.account, self.run = account, run
        self.stop = threading.Event()
        self.booked: list[str] = []
        self.failures: list[str] = []
        self.requests = 0

    def run(self) -> None:
        number = 0
        while not self.stop.is_set():
            number += 1
            try:
                # A quote and a booking (the benchmark harness's), then the public tracking view.
                tracking, _ = harness.book(self.account, f"rotation-{self.run}", number)
                harness.call("customer-hanseatic-imports", "GET", f"/api/v1/shipments/track/{tracking}")
                self.booked.append(tracking)
            except Exception as error:  # noqa: BLE001 every failure counts, with its reason
                self.failures.append(f"{time.strftime('%H:%M:%S')} {type(error).__name__}: {error}")
            self.requests += 3
            self.stop.wait(1.0)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", help="write the evidence here")
    parser.add_argument("--switch-timeout", type=float, default=180)
    args = parser.parse_args()

    run = str(int(time.time()))
    recipient, account = harness.setup(run)
    before = served()
    traffic = Traffic(account, run)
    traffic.start()
    time.sleep(20)
    renewed_at = time.monotonic()
    subprocess.run([*COMPOSE, "exec", "-T", "cert-agent", "sh", "/etc/freightflow/tls/agent.sh", "renew-all"],
                   check=True, capture_output=True, text=True, timeout=300)
    print(f"every certificate renewed in {time.monotonic() - renewed_at:.0f} s; waiting for the endpoints", flush=True)

    switched: dict[str, float] = {}
    pending = {endpoint for endpoint in before if endpoint not in SLOW_RELOAD}
    while pending and time.monotonic() - renewed_at < args.switch_timeout:
        now = served()
        for endpoint in list(pending):
            if not now[endpoint].startswith("error") and now[endpoint] != before[endpoint]:
                switched[endpoint] = round(time.monotonic() - renewed_at)
                pending.discard(endpoint)
        if pending:
            time.sleep(5)
    time.sleep(20)
    traffic.stop.set()
    traffic.join(timeout=60)

    emails = booking_emails(recipient, set(traffic.booked), 180) if traffic.booked else set()
    missing = sorted(set(traffic.booked) - emails)
    for endpoint in sorted(before):
        state = (f"new certificate after {switched[endpoint]} s" if endpoint in switched
                 else "re-reads hourly, not checked" if endpoint in SLOW_RELOAD else "STILL THE OLD CERTIFICATE")
        print(f"{endpoint:28} {state}")
    print(f"{len(traffic.booked)} bookings, {traffic.requests} requests, {len(traffic.failures)} failed; "
          f"{len(traffic.booked) - len(missing)} of {len(traffic.booked)} booking emails arrived")
    for failure in traffic.failures:
        print(f"  {failure}")
    if args.json:
        Path(args.json).write_text(json.dumps({
            "switched_after_seconds": switched, "not_switched": sorted(pending), "bookings": len(traffic.booked),
            "requests": traffic.requests, "failures": traffic.failures, "emails_missing": missing}, indent=2))
    sys.exit(1 if pending or traffic.failures or missing or not traffic.booked else 0)


if __name__ == "__main__":
    main()
