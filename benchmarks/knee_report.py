"""Reads the knee runs' k6 summaries (benchmarks/k6/booking_knee.js) and prints the table and the knee.

    python benchmarks/knee_report.py benchmarks/out/knee     # exit 0; the verdict is in the table

A rate keeps up when p95 of the booking is within the 500 ms SLO, at most 0.5 % failed, and k6 dropped nothing.
The knee is the highest rate that kept up, with the first that did not beside it.
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path


def metric(data: dict, name: str, field: str, default: float = 0.0) -> float:
    return float(data["metrics"].get(name, {}).get("values", {}).get(field, default))


def main() -> None:
    directory = Path(sys.argv[1])
    rows = []
    for path in sorted(directory.glob("knee-*.json"), key=lambda p: int(re.search(r"(\d+)", p.stem).group(1))):
        data = json.loads(path.read_text())
        rate = int(re.search(r"(\d+)", path.stem).group(1))
        accepted, failed = metric(data, "bookings_accepted", "count"), metric(data, "bookings_failed", "count")
        dropped = metric(data, "dropped_iterations", "count")
        total = accepted + failed
        p95 = metric(data, "booking_duration", "p(95)")
        keeps_up = p95 <= 500 and (failed / total if total else 1) <= 0.005 and dropped == 0
        rows.append({"rate": rate, "achieved": round(accepted / (total / rate) if total else 0, 1) if total else 0,
                     "accepted": int(accepted), "failed": int(failed), "dropped": int(dropped),
                     "p50": round(metric(data, "booking_duration", "med"), 1), "p95": round(p95, 1),
                     "p99": round(metric(data, "booking_duration", "p(99)"), 1), "keeps_up": keeps_up})
    print("| Offered /s | Accepted | Failed | Dropped by k6 | p50 ms | p95 ms | p99 ms | Keeps up |")
    print("| ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |")
    for r in rows:
        print(f"| {r['rate']} | {r['accepted']} | {r['failed']} | {r['dropped']} | {r['p50']} | {r['p95']} | "
              f"{r['p99']} | {'yes' if r['keeps_up'] else 'no'} |")
    kept = [r["rate"] for r in rows if r["keeps_up"]]
    missed = [r["rate"] for r in rows if not r["keeps_up"]]
    print()
    print(f"Knee: {max(kept) if kept else 'below the first rate'} bookings/s kept up"
          + (f"; {min(m for m in missed if not kept or m > max(kept))}/s did not." if missed else
             "; every rate tried kept up."))
    (directory / "knee.json").write_text(json.dumps(rows, indent=2))


if __name__ == "__main__":
    main()
