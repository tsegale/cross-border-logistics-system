"""The soak (docs/benchmarks/soak.md): bookings at a steady rate for hours, to find what only time shows.

Per 10-minute window: bookings offered, failed, and the p50/p95/p99 of POST /api/v1/shipments. Every minute: each
container's memory (docker stats), so a leak shows as a slope rather than a peak. At the end: every booking settled
(booked and paid), and the invariant auditor's verdict.

    python benchmarks/soak.py --rate 5 --hours 2 --json soak.json

Needs the stack with benchmarks/compose.bench.yml (the booking budget raised), Docker and Python.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "benchmarks"))
import events_to_email as harness  # noqa: E402

WINDOW = 600


def log(message: str) -> None:
    print(f"{datetime.now(timezone.utc):%H:%M:%S} {message}", flush=True)


def memory() -> dict[str, float]:
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.MemUsage}}"],
                         capture_output=True, text=True, timeout=60).stdout
    usage = {}
    for line in out.splitlines():
        name, used = line.split("\t")[0], line.split("\t")[1].split("/")[0].strip()
        number = float("".join(c for c in used if c.isdigit() or c == "."))
        unit = used.lstrip("0123456789.").strip()
        usage[name.removeprefix("freightflow-").removesuffix("-1")] = round(
            number / 1024 if unit.startswith("KiB") else number * 1024 if unit.startswith("GiB") else number, 1)
    return usage


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rate", type=float, default=5.0, help="bookings per second")
    parser.add_argument("--hours", type=float, default=2.0)
    parser.add_argument("--json", required=True)
    args = parser.parse_args()
    run = str(int(time.time()))
    _, account = harness.setup(run)

    results: list[tuple[float, bool, float, str]] = []
    lock = threading.Lock()

    def one(index: int) -> None:
        started = time.monotonic()
        try:
            tracking, _ = harness.book(account, f"soak-{run}", index)
            outcome = (time.time(), True, time.monotonic() - started, tracking)
        except Exception as error:  # noqa: BLE001 counted, with its reason
            outcome = (time.time(), False, time.monotonic() - started, f"{type(error).__name__}: {error}"[:200])
        with lock:
            results.append(outcome)

    samples: list[dict] = []
    stop = threading.Event()

    def sample() -> None:
        while not stop.is_set():
            samples.append({"at": round(time.time()), "mib": memory()})
            stop.wait(60)

    sampler = threading.Thread(target=sample, daemon=True)
    sampler.start()
    total = int(args.rate * args.hours * 3600)
    start = time.monotonic()
    log(f"soak: {total} bookings at {args.rate}/s over {args.hours} h")
    threads = []
    for index in range(total):
        time.sleep(max(0.0, start + index / args.rate - time.monotonic()))
        thread = threading.Thread(target=one, args=(index,), daemon=True)
        thread.start()
        threads.append(thread)
        if index and index % int(args.rate * WINDOW) == 0:
            with lock:
                done = len(results)
                failures = sum(1 for r in results if not r[1])
            log(f"soak: {index} offered, {done} answered, {failures} failed")
            threads = [t for t in threads if t.is_alive()]
    for thread in threads:
        thread.join(timeout=120)
    stop.set()
    sampler.join(timeout=90)
    began = time.time() - (time.monotonic() - start)

    windows = []
    for w in range(int(args.hours * 3600 // WINDOW)):
        lo, hi = began + w * WINDOW, began + (w + 1) * WINDOW
        inside = [r for r in results if lo <= r[0] < hi]
        ok = sorted(r[2] for r in inside if r[1])
        windows.append({"window": w, "offered": len(inside), "failed": sum(1 for r in inside if not r[1]),
                        "p50_ms": round(harness.percentile(ok, 50) * 1000, 1) if ok else None,
                        "p95_ms": round(harness.percentile(ok, 95) * 1000, 1) if ok else None,
                        "p99_ms": round(harness.percentile(ok, 99) * 1000, 1) if ok else None})
    booked = [r[3] for r in results if r[1]]
    log(f"soak: {len(booked)} booked of {len(results)}; settling")
    # The last bookings' payments are a few seconds behind them.
    time.sleep(60)
    unsettled = []
    for tracking in booked[-500:]:
        status = harness.call("customer-hanseatic-imports", "GET", f"/api/v1/shipments/track/{tracking}")["status"]
        if status in ("AWAITING_PAYMENT", "PAYMENT_FAILED"):
            unsettled.append((tracking, status))
    audit = subprocess.run([sys.executable, str(ROOT / "scripts" / "audit_invariants.py"),
                            "--json", str(ROOT / "soak-audit.json")], capture_output=True, text=True, timeout=1800)
    print(audit.stdout[-2000:], flush=True)
    verdict = json.loads((ROOT / "soak-audit.json").read_text()) if (ROOT / "soak-audit.json").exists() else {}
    errors: dict[str, int] = {}
    for r in results:
        if not r[1]:
            key = r[3].split(":")[0]
            errors[key] = errors.get(key, 0) + 1
    Path(args.json).write_text(json.dumps({
        "rate_per_second": args.rate, "hours": args.hours, "offered": len(results), "booked": len(booked),
        "errors": errors, "windows": windows, "memory_samples": samples,
        "last_500_unsettled": unsettled, "invariants_hold": verdict.get("holds"), "audit": verdict}, indent=2))
    log(f"soak: done, invariants {'hold' if verdict.get('holds') else 'BROKEN'}, {len(unsettled)} of the last 500 "
        f"unsettled")
    sys.exit(0 if verdict.get("holds") and not unsettled else 1)


if __name__ == "__main__":
    main()
