#!/usr/bin/env python3
"""One booking, one trace, end to end (ADR 0030), checked against the running observability stack (ADR 0032).

Books a shipment through the gateway with a request id of its own, then:

1. finds the booking's trace in Tempo by that request id (the gateway's server span carries freightflow.request_id)
   and fails unless it holds every service and every asynchronous hop: the gateway, logistics' booking, the payment
   relay's call to banking, banking's hold, both outbox relays, Kafka producers and consumers, and the email's send;
2. fails unless Loki has log lines of that trace id from the services, and none of them holds an email address or a
   bearer token (ADR 0024);
3. fails unless Prometheus has the business metrics (ADR 0031), exemplars on the latency buckets, and the alert rules
   loaded (ADR 0033).

Needs the stack with --profile observability and infra/observability/observability.env, and the published ports of
Tempo (3200), Loki (3100) and Prometheus (9090) on 127.0.0.1. Standard library only.

    python3 scripts/trace_booking.py --json trace.json
"""
from __future__ import annotations

import argparse
import json
import re
import sys
import time
import urllib.parse
import urllib.request
from datetime import date, timedelta
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "benchmarks"))
import events_to_email as harness  # noqa: E402

TEMPO = "http://127.0.0.1:3200"
LOKI = "http://127.0.0.1:3100"
PROMETHEUS = "http://127.0.0.1:9090"

SERVICES = {"api-gateway", "logistics-service", "banking-service", "notification-service"}
SPANS = {"http post /api/v1/shipments", "payment-command send", "http post /api/v1/holds", "event-outbox relay",
         "email send"}
METRICS = [
    "freightflow_shipments_booked_total", "freightflow_shipments_payment_state", "freightflow_holds_total",
    "freightflow_money_moved_total", "freightflow_journal_entries_total", "freightflow_approvals_pending",
    "freightflow_approvals_expiring", "freightflow_ledger_discrepancies", "freightflow_ledger_reconciliation_age_seconds",
    "freightflow_payment_commands_parked", "freightflow_outbox_backlog", "freightflow_outbox_oldest_age_seconds",
    "freightflow_outbox_lag_seconds_bucket", "freightflow_dead_letters", "freightflow_emails",
    "freightflow_emails_sent_total", "freightflow_emails_end_to_end_seconds_bucket", "kafka_consumer_group_lag",
    "postgresql_backends", "redis_memory_used_bytes", "container_memory_usage_total_bytes",
    "container_memory_usage_limit_bytes", "http_server_requests_seconds_bucket", "jvm_memory_used_bytes",
    "hikaricp_connections_active",
]
EMAIL = re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")
TOKEN = re.compile(r"(?i)bearer\s+[A-Za-z0-9._~+/-]{8,}")


def get(url: str) -> dict:
    with urllib.request.urlopen(url, timeout=20) as response:
        return json.loads(response.read() or b"{}")


def wait(what: str, seconds: float, probe):
    deadline = time.monotonic() + seconds
    last = None
    while time.monotonic() < deadline:
        try:
            last = probe()
            if last:
                return last
        except (OSError, ValueError) as error:
            last = error
        time.sleep(3)
    raise SystemExit(f"trace_booking: {what} not found within {seconds:.0f} s (last: {last})")


def book(account: str, run: str, request_id: str) -> str:
    quote = harness.call("customer-hanseatic-imports", "POST", "/api/v1/quotes", harness.QUOTE, expect=201)["id"]
    shipment = harness.call("customer-hanseatic-imports", "POST", "/api/v1/shipments", {
        "quoteId": quote,
        # Contacts are personal data (ADR 0024): they must not reach a span or a log line.
        "shipper": {"name": "Namib Fisheries", "address": "Walvis Bay", "contact": f"shipping-{run}@namib-fish.example"},
        "consignee": {"name": "Hanseatic Seafood", "address": "Hamburg", "contact": f"ops-{run}@hanseatic.example"},
        "payerAccountNumber": account, "goodsDescription": "Frozen hake", "declaredValue": 18500.00,
        "declaredValueCurrency": "EUR", "plannedPickupDate": (date.today() + timedelta(days=7)).isoformat(),
    }, headers={"Idempotency-Key": f"trace-{run}", "X-Request-Id": request_id}, expect=201)
    return shipment["id"]


def spans_of(trace: dict) -> list[dict]:
    """(service, name, kind) of every span, from Tempo's OTLP JSON (v1 "batches" or v2 "trace.resourceSpans")."""
    batches = trace.get("batches") or trace.get("resourceSpans") or (trace.get("trace") or {}).get("resourceSpans", [])
    spans = []
    for batch in batches:
        attributes = {a["key"]: a["value"].get("stringValue") for a in batch.get("resource", {}).get("attributes", [])}
        service = attributes.get("service.name")
        for scope in batch.get("scopeSpans", batch.get("instrumentationLibrarySpans", [])):
            for span in scope.get("spans", []):
                spans.append({"service": service, "name": span.get("name"), "kind": span.get("kind"),
                              "attributes": {a["key"]: next(iter(a["value"].values()), None)
                                             for a in span.get("attributes", [])}})
    return spans


def tempo_trace(request_id: str, started: float) -> tuple[str, list[dict]]:
    query = urllib.parse.urlencode({"q": f'{{ span.freightflow.request_id = "{request_id}" }}',
                                    "start": int(started) - 60, "end": int(time.time()) + 600, "limit": 5})

    def found():
        traces = get(f"{TEMPO}/api/search?{query}").get("traces", [])
        return traces[0]["traceID"] if traces else None

    trace_id = wait("the booking's trace in Tempo", 120, found)

    def complete():
        spans = spans_of(get(f"{TEMPO}/api/traces/{trace_id}"))
        names = {s["name"] for s in spans}
        return spans if SPANS <= names and SERVICES <= {s["service"] for s in spans} else None

    try:
        return trace_id, wait("every expected span", 120, complete)
    except SystemExit:
        spans = spans_of(get(f"{TEMPO}/api/traces/{trace_id}"))
        print(json.dumps(sorted({(s["service"], s["name"]) for s in spans}), indent=1), file=sys.stderr)
        raise


def loki_lines(trace_id: str, started: float) -> list[tuple[str, str]]:
    query = urllib.parse.urlencode({"query": f'{{service_name=~".+"}} | trace_id="{trace_id}"', "limit": 1000,
                                    "start": int((started - 60) * 1e9), "end": int((time.time() + 600) * 1e9)})

    def found():
        result = get(f"{LOKI}/loki/api/v1/query_range?{query}")["data"]["result"]
        lines = [(stream["stream"].get("service_name", "?"), value[1]) for stream in result
                 for value in stream["values"]]
        return lines if len({service for service, _ in lines}) >= 3 else None

    return wait("the trace's log lines in Loki", 90, found)


def prometheus_checks() -> dict:
    def present(metric: str) -> bool:
        query = urllib.parse.urlencode({"query": f"count({metric})"})
        return bool(get(f"{PROMETHEUS}/api/v1/query?{query}")["data"]["result"])

    deadline = time.monotonic() + 120
    missing = [m for m in METRICS if not present(m)]
    while missing and time.monotonic() < deadline:
        time.sleep(5)
        missing = [m for m in missing if not present(m)]
    if missing:
        raise SystemExit(f"trace_booking: metrics missing from Prometheus: {missing}")
    now = time.time()
    exemplars = get(f"{PROMETHEUS}/api/v1/query_exemplars?" + urllib.parse.urlencode(
        {"query": 'http_server_requests_seconds_bucket{service="logistics-service"}', "start": now - 900, "end": now}))
    traced = [e for series in exemplars["data"] for e in series.get("exemplars", []) if "trace_id" in e["labels"]]
    if not traced:
        raise SystemExit("trace_booking: no exemplar with a trace_id on logistics' latency buckets")
    rules = get(f"{PROMETHEUS}/api/v1/rules")["data"]["groups"]
    alerts = [rule["name"] for group in rules for rule in group["rules"] if rule["type"] == "alerting"]
    if len(alerts) < 20:
        raise SystemExit(f"trace_booking: only {len(alerts)} alert rules loaded")
    return {"metrics": len(METRICS), "exemplars_with_trace_id": len(traced), "alert_rules": len(alerts)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", help="write the result here")
    args = parser.parse_args()

    run = str(int(time.time()))
    request_id = f"trace-booking-{run}"
    _, account = harness.setup(run)
    started = time.time()
    shipment = book(account, run, request_id)
    print(f"booked shipment {shipment} with X-Request-Id {request_id}")

    trace_id, spans = tempo_trace(request_id, started)
    services = sorted({s["service"] for s in spans})
    kinds = sorted({str(s["kind"]) for s in spans})
    consumers = sorted({s["service"] for s in spans if "CONSUMER" in str(s["kind"]) or s["kind"] == 5})
    if not {"logistics-service", "notification-service"} <= set(consumers):
        raise SystemExit(f"trace_booking: Kafka consumer spans only in {consumers}")
    leaks = [f"{s['service']} {s['name']} {k}={v}" for s in spans for k, v in s["attributes"].items()
             if isinstance(v, str) and (EMAIL.search(v) or TOKEN.search(v))]
    if leaks:
        raise SystemExit(f"trace_booking: personal data or a token in spans: {leaks[:5]}")
    print(f"trace {trace_id}: {len(spans)} spans from {services}")

    lines = loki_lines(trace_id, started)
    leaked = [line for _, line in lines if EMAIL.search(line) or TOKEN.search(line)]
    if leaked:
        raise SystemExit(f"trace_booking: personal data or a token in logs: {leaked[:3]}")
    log_services = sorted({service for service, _ in lines})
    print(f"loki: {len(lines)} lines of the trace from {log_services}")

    prometheus = prometheus_checks()
    print(f"prometheus: {prometheus}")

    result = {"trace_id": trace_id, "request_id": request_id, "shipment": shipment, "spans": len(spans),
              "services": services, "span_kinds": kinds, "consumers": consumers,
              "span_names": sorted({s["name"] for s in spans}), "log_lines": len(lines),
              "log_services": log_services, **prometheus}
    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
