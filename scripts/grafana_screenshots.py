#!/usr/bin/env python3
"""Screenshots of the observability stack for the README (ADR 0032): one booking's trace waterfall, the booking-to-email
dashboard, and the alerts that fired. Run after scripts/trace_booking.py (for the trace id) and, for the alerts, during
or after the fire drill. Needs Playwright with Chromium (`pip install playwright==1.65.2 && playwright install chromium`).

    python3 scripts/grafana_screenshots.py --trace-json trace.json --out docs/images
"""
from __future__ import annotations

import argparse
import json
import urllib.parse
from pathlib import Path

from playwright.sync_api import sync_playwright

GRAFANA = "http://127.0.0.1:3000"
PROMETHEUS = "http://127.0.0.1:9090"


def explore_trace(trace_id: str) -> str:
    pane = {"datasource": "tempo", "queries": [{"refId": "A", "datasource": {"type": "tempo", "uid": "tempo"},
                                                "queryType": "traceql", "query": trace_id}],
            "range": {"from": "now-1h", "to": "now"}}
    return f"{GRAFANA}/explore?schemaVersion=1&panes=" + urllib.parse.quote(json.dumps({"t": pane}))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--trace-json", help="trace_booking.py's output, for the trace id")
    parser.add_argument("--out", default="docs/images")
    parser.add_argument("--alerts", action="store_true", help="also the firing alerts")
    args = parser.parse_args()
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    shots = [("observability-flow-dashboard.png", f"{GRAFANA}/d/booking-to-email?orgId=1&from=now-30m&to=now&kiosk", 12000)]
    if args.trace_json:
        trace_id = json.loads(Path(args.trace_json).read_text())["trace_id"]
        shots.insert(0, ("observability-booking-trace.png", explore_trace(trace_id), 12000))
    if args.alerts:
        shots.append(("observability-fired-alerts.png", f"{PROMETHEUS}/alerts?state=firing", 5000))
        # Alertmanager's emails to the on-call address, in Mailpit (benchmarks/compose.bench.yml publishes it).
        shots.append(("observability-alert-email.png", "http://127.0.0.1:8025/search?q=to%3Aon-call%40freightflow.dev", 5000))

    with sync_playwright() as playwright:
        browser = playwright.chromium.launch()
        page = browser.new_page(viewport={"width": 1600, "height": 1000}, color_scheme="dark")
        for name, url, settle_ms in shots:
            page.goto(url, wait_until="networkidle", timeout=60000)
            page.wait_for_timeout(settle_ms)
            page.screenshot(path=str(out / name), full_page=False)
            print(f"wrote {out / name}")
        browser.close()


if __name__ == "__main__":
    main()
