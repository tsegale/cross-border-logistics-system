#!/usr/bin/env python3
"""First-request latency through the gateway right after the stack reports ready.

Run straight after `docker compose up -d --wait`, before anything else touches the services: the first pass is what
the first users of a fresh deployment would get, the second pass is the same requests warm. Tokens are fetched
before any timing starts, so Keycloak's own cold start is not counted.

    python3 benchmarks/first_requests.py                 # table on stdout
    python3 benchmarks/first_requests.py --json out.json

Read-only requests plus a quote (which calls banking for the indicative FX rate): nothing books or moves money, so
the smoke test that follows sees the stack as if this had not run. Standard library only.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import keycloak_token  # noqa: E402
from internal_ca import ssl_context  # noqa: E402

# HTTPS, verified against the internal CA's root (ADR 0039).
GATEWAY = os.environ.get("GATEWAY_URL", "https://127.0.0.1:8080")
QUOTE = json.dumps({
    "origin": "NAWVB", "destination": "DEHAM", "mode": "SEA_LCL", "incoterm": "FCA", "payerRole": "CONSIGNEE",
    "packages": [{"lengthCm": 120, "widthCm": 100, "heightCm": 150, "weightKg": 500, "quantity": 2}],
    "indicativeCurrency": "NAD",
}).encode()

# (persona, method, path, body, the services it reaches)
REQUESTS = [
    ("customer-namib-exports", "GET", "/api/v1/fx-rates?base=EUR", None, "banking"),
    ("customer-namib-exports", "POST", "/api/v1/quotes", QUOTE, "logistics, banking"),
    ("customer-namib-exports", "GET", "/api/v1/shipments", None, "logistics"),
    ("auditor", "GET", "/api/v1/approvals", None, "banking"),
    ("operator", "GET", "/api/v1/ledger/reconciliation", None, "banking"),
    ("operator", "GET", "/api/v1/payment-commands", None, "logistics"),
]


def timed(token: str, method: str, path: str, body: bytes | None) -> tuple[int, float]:
    request = urllib.request.Request(GATEWAY + path, data=body, method=method, headers={
        "Authorization": f"Bearer {token}", "Accept": "application/json",
        **({"Content-Type": "application/json"} if body else {})})
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=60, context=ssl_context()) as response:
            response.read()
            status = response.status
    except urllib.error.HTTPError as error:
        error.read()
        status = error.code
    return status, (time.perf_counter() - started) * 1000


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", help="also write the results to this file")
    args = parser.parse_args()

    tokens = {persona: keycloak_token.token_for(persona) for persona in {r[0] for r in REQUESTS}}
    rows = []
    for persona, method, path, body, reaches in REQUESTS:
        cold_status, cold_ms = timed(tokens[persona], method, path, body)
        rows.append({"request": f"{method} {path}", "reaches": reaches, "cold_status": cold_status, "cold_ms": cold_ms})
    for row, (persona, method, path, body, _) in zip(rows, REQUESTS):
        row["warm_status"], row["warm_ms"] = timed(tokens[persona], method, path, body)

    print("| Request | Reaches | First call | Second call |")
    print("| --- | --- | ---: | ---: |")
    for row in rows:
        print(f"| `{row['request']}` | {row['reaches']} | {row['cold_ms']:.0f} ms ({row['cold_status']}) "
              f"| {row['warm_ms']:.0f} ms ({row['warm_status']}) |")
    print(f"\nFirst pass total {sum(r['cold_ms'] for r in rows):.0f} ms, "
          f"second pass total {sum(r['warm_ms'] for r in rows):.0f} ms")
    if args.json:
        Path(args.json).write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
    failed = [r for r in rows if r["cold_status"] >= 400 or r["warm_status"] >= 400]
    if failed:
        raise SystemExit(f"unexpected status: {failed}")


if __name__ == "__main__":
    main()
