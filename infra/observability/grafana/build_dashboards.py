#!/usr/bin/env python3
"""Builds the Grafana dashboards (ADR 0032) as JSON into dashboards/: one RED dashboard per service, the
booking-to-email flow, money, Kafka and runtime. The JSON files are committed and provisioned at start; CI rebuilds
them and fails if they differ, and checks every panel's query names a metric and has a link to traces or logs.

    python3 infra/observability/grafana/build_dashboards.py
"""
from __future__ import annotations

import json
from pathlib import Path

OUT = Path(__file__).resolve().parent / "dashboards"
SERVICES = ["api-gateway", "banking-service", "logistics-service", "notification-service"]

# Explore links, so every panel leads to the matching traces or logs. Grafana fills ${__from} and ${__to}.
def traces_link(service: str | None = None) -> dict:
    query = f'{{resource.service.name="{service}"}}' if service else "{}"
    pane = {"datasource": "tempo", "queries": [{"refId": "A", "datasource": {"type": "tempo", "uid": "tempo"},
                                                "queryType": "traceql", "query": query}],
            "range": {"from": "${__from}", "to": "${__to}"}}
    return {"title": f"Traces{' of ' + service if service else ''} in Tempo", "targetBlank": True,
            "url": "/explore?schemaVersion=1&panes=" + json.dumps({"t": pane}, separators=(",", ":"))}


def logs_link(service: str | None = None, extra: str = "") -> dict:
    selector = f'{{service_name="{service}"}}' if service else '{service_name=~".+"}'
    pane = {"datasource": "loki", "queries": [{"refId": "A", "datasource": {"type": "loki", "uid": "loki"},
                                               "expr": selector + extra}],
            "range": {"from": "${__from}", "to": "${__to}"}}
    return {"title": f"Logs{' of ' + service if service else ''} in Loki", "targetBlank": True,
            "url": "/explore?schemaVersion=1&panes=" + json.dumps({"l": pane}, separators=(",", ":"))}


class Board:
    def __init__(self, uid: str, title: str, description: str, tags: list[str]):
        self.uid, self.title, self.description, self.tags = uid, title, description, tags
        self.panels: list[dict] = []
        self.y = 0
        self.x = 0

    def row(self, title: str) -> None:
        if self.x:
            self.y += 8
            self.x = 0
        self.panels.append({"type": "row", "title": title, "collapsed": False, "id": len(self.panels) + 1,
                            "gridPos": {"h": 1, "w": 24, "x": 0, "y": self.y}, "panels": []})
        self.y += 1

    def panel(self, title: str, description: str, targets: list[tuple[str, str]], unit: str = "short",
              kind: str = "timeseries", width: int = 12, links: list[dict] | None = None,
              exemplars: bool = False, thresholds: list[tuple[float, str]] | None = None) -> None:
        if self.x + width > 24:
            self.y += 8
            self.x = 0
        steps = [{"color": "green", "value": None}] + [{"color": c, "value": v} for v, c in (thresholds or [])]
        panel = {
            "id": len(self.panels) + 1, "type": kind, "title": title, "description": description,
            "datasource": {"type": "prometheus", "uid": "prometheus"},
            "gridPos": {"h": 8, "w": width, "x": self.x, "y": self.y},
            "links": links or [traces_link(), logs_link()],
            "fieldConfig": {"defaults": {"unit": unit, "thresholds": {"mode": "absolute", "steps": steps}},
                            "overrides": []},
            "options": {"legend": {"displayMode": "list", "placement": "bottom", "showLegend": True},
                        "tooltip": {"mode": "multi", "sort": "desc"}},
            "targets": [{"refId": chr(65 + i), "datasource": {"type": "prometheus", "uid": "prometheus"},
                         "expr": expr, "legendFormat": legend, "exemplar": exemplars}
                        for i, (expr, legend) in enumerate(targets)],
        }
        if kind == "stat":
            panel["options"] = {"reduceOptions": {"calcs": ["lastNotNull"], "fields": "", "values": False},
                                "colorMode": "background", "graphMode": "none", "textMode": "auto"}
        self.panels.append(panel)
        self.x += width

    def json(self) -> dict:
        return {
            "uid": self.uid, "title": self.title, "description": self.description, "tags": ["freightflow"] + self.tags,
            "schemaVersion": 41, "version": 1, "editable": False, "graphTooltip": 1, "refresh": "30s",
            "time": {"from": "now-1h", "to": "now"}, "timezone": "utc", "panels": self.panels,
            "templating": {"list": []}, "annotations": {"list": []},
            "links": [{"title": "FreightFlow", "type": "dashboards", "tags": ["freightflow"], "asDropdown": True}],
        }


def red(service: str) -> Board:
    s = f'service="{service}"'
    metric = "spring_cloud_gateway_requests_seconds" if service == "api-gateway" else "http_server_requests_seconds"
    by = "routeId" if service == "api-gateway" else "method, uri"
    legend = "{{routeId}}" if service == "api-gateway" else "{{method}} {{uri}}"
    actuator = "" if service == "api-gateway" else ',uri!~"/actuator.*"'
    links = [traces_link(service), logs_link(service)]
    b = Board(f"red-{service}", f"RED: {service}",
              f"Rate, errors and duration of {service}'s requests (docs/slo.md). Latency panels show exemplars: "
              "a dot links to a trace that landed in that bucket.", ["red"])
    b.row("Rate, errors, duration")
    b.panel("Requests per second", "By endpoint.",
            [(f"sum by ({by}) (rate({metric}_count{{{s}{actuator}}}[$__rate_interval]))", legend)], "reqps",
            links=links)
    b.panel("Server errors (5xx share)", "Share of requests that ended in a server error.",
            [(f'sum by ({by}) (rate({metric}_count{{{s},outcome="SERVER_ERROR"}}[$__rate_interval])) / '
              f"sum by ({by}) (rate({metric}_count{{{s}{actuator}}}[$__rate_interval]))", legend)], "percentunit",
            links=[traces_link(service), logs_link(service, ' | detected_level="error"')],
            thresholds=[(0.005, "orange"), (0.05, "red")])
    b.panel("p95 latency", "95th percentile from the histogram buckets, with exemplars.",
            [(f"histogram_quantile(0.95, sum by (le, {by}) (rate({metric}_bucket{{{s}{actuator}}}[$__rate_interval])))",
              legend)], "s", links=links, exemplars=True, thresholds=[(0.5, "red")])
    b.panel("p99 latency", "99th percentile, with exemplars.",
            [(f"histogram_quantile(0.99, sum by (le, {by}) (rate({metric}_bucket{{{s}{actuator}}}[$__rate_interval])))",
              legend)], "s", links=links, exemplars=True)
    b.panel("Client errors (4xx share)", "Refusals the caller caused: validation, authorisation, conflicts.",
            [(f'sum by ({by}) (rate({metric}_count{{{s},outcome="CLIENT_ERROR"}}[$__rate_interval]))', legend)],
            "reqps", links=links)
    if service != "api-gateway":
        b.panel("Outgoing HTTP p95", "Calls this service makes (logistics to banking, token fetches).",
                [(f"histogram_quantile(0.95, sum by (le, uri) (rate(http_client_requests_seconds_bucket{{{s}}}[$__rate_interval])))",
                  "{{uri}}")], "s", links=links, exemplars=True)
    return b


def flow() -> Board:
    b = Board("booking-to-email", "Booking to email",
              "One booking through every stage (ADR 0030): booked in logistics, its payment command to banking, the "
              "outboxes and Kafka, notification's consumer and the email. Each stage's rate and latency; a latency "
              "exemplar opens that booking's whole trace.", ["flow"])
    b.row("Stages")
    b.panel("Bookings per minute", "POST /api/v1/shipments that committed.",
            [("sum(rate(freightflow_shipments_booked_total[$__rate_interval])) * 60", "booked")], "short",
            links=[traces_link("logistics-service"), logs_link("logistics-service")])
    b.panel("Booking latency p95", "POST /api/v1/shipments in logistics; the objective is 500 ms (docs/slo.md).",
            [('histogram_quantile(0.95, sum by (le) (rate(http_server_requests_seconds_bucket{service="logistics-service",'
              'uri="/api/v1/shipments",method="POST"}[$__rate_interval])))', "p95")], "s",
            links=[traces_link("logistics-service")], exemplars=True, thresholds=[(0.5, "red")])
    b.panel("Payment commands by outcome", "The saga's calls to banking (ADR 0012).",
            [("sum by (command, outcome) (rate(freightflow_payment_commands_total[$__rate_interval])) * 60",
              "{{command}} {{outcome}}")], "short", links=[traces_link("logistics-service"), logs_link("logistics-service")])
    b.panel("Shipments by payment state", "Where shipments wait in paying for themselves.",
            [("max by (state) (freightflow_shipments_payment_state)", "{{state}}")], "short",
            links=[traces_link("logistics-service")])
    b.panel("Outbox lag p95 (commit to broker)", "Per relay (ADR 0025).",
            [("histogram_quantile(0.95, sum by (le, service) (rate(freightflow_outbox_lag_seconds_bucket[$__rate_interval])))",
              "{{service}}")], "s", exemplars=True)
    b.panel("Outbox backlog and oldest row", "Rows not yet relayed, and how old the oldest is.",
            [("max by (service, outbox) (freightflow_outbox_backlog)", "backlog {{service}} {{outbox}}"),
             ("max by (service, outbox) (freightflow_outbox_oldest_age_seconds)", "oldest s {{service}} {{outbox}}")],
            "short", thresholds=[(60, "red")])
    b.panel("Consumer lag", "Records behind, per group and topic (the collector's kafka_metrics).",
            [("sum by (group, topic) (kafka_consumer_group_lag)", "{{group}} {{topic}}")], "short",
            thresholds=[(100, "red")])
    b.panel("Events consumed by outcome", "logistics and notification consumers (ADR 0026).",
            [("sum by (service, type, outcome) (rate(freightflow_events_consumed_total[$__rate_interval])) * 60",
              "{{service}} {{type}} {{outcome}}")], "short")
    b.panel("Booking to email p99", "From the booking's commit to the mail server's acceptance; 99 % within 60 s "
            "is the objective (docs/slo.md).",
            [("histogram_quantile(0.99, sum by (le) (rate(freightflow_emails_end_to_end_seconds_bucket[$__rate_interval])))",
              "p99"), ("histogram_quantile(0.50, sum by (le) (rate(freightflow_emails_end_to_end_seconds_bucket[$__rate_interval])))",
                       "p50")], "s", links=[traces_link("notification-service"), logs_link("notification-service")],
            exemplars=True, thresholds=[(60, "red")])
    b.panel("Emails by status", "PENDING waits, FAILED was refused every time, UNKNOWN may not have arrived.",
            [("max by (status) (freightflow_emails)", "{{status}}"),
             ("sum(rate(freightflow_emails_sent_total[$__rate_interval])) * 60", "sent per minute")], "short",
            links=[logs_link("notification-service")])
    return b


def money() -> Board:
    b = Board("money", "Money", "The ledger in numbers (ADR 0004, ADR 0011, ADR 0021). Reconciliation drift must be "
              "zero: any other value pages (docs/slo.md).", ["money"])
    b.row("Ledger")
    b.panel("Reconciliation drift", "Accounts off their postings, and currencies whose postings do not sum to zero.",
            [("max(freightflow_ledger_discrepancies)", "accounts"),
             ("max(freightflow_ledger_unbalanced_currencies)", "currencies")], "short", kind="stat", width=8,
            links=[logs_link("banking-service", ' |= "reconciliation"')], thresholds=[(1, "red")])
    b.panel("Last reconciliation", "Seconds since the scheduled check last ran (every 5 minutes).",
            [("max(freightflow_ledger_reconciliation_age_seconds)", "age")], "s", kind="stat", width=8,
            links=[logs_link("banking-service")], thresholds=[(900, "red")])
    b.panel("Approvals waiting", "Deposits and reversals waiting for a second operator, and those expiring within "
            "the hour.", [("max(freightflow_approvals_pending)", "waiting"),
                          ("max(freightflow_approvals_expiring)", "expire within 1 h")], "short", kind="stat",
            width=8, links=[traces_link("banking-service"), logs_link("banking-service")])
    b.panel("Money moved per currency", "Debit side of each posted entry per minute, by currency and entry type.",
            [("sum by (currency, entry_type) (rate(freightflow_money_moved_total[$__rate_interval])) * 60",
              "{{currency}} {{entry_type}}")], "short", links=[traces_link("banking-service")])
    b.panel("Journal entries per minute", "By entry type.",
            [("sum by (entry_type) (rate(freightflow_journal_entries_total[$__rate_interval])) * 60",
              "{{entry_type}}")], "short", links=[traces_link("banking-service")])
    b.panel("Holds by event", "Authorised, extended, captured, released and expired, per minute (ADR 0011).",
            [("sum by (event) (rate(freightflow_holds_total[$__rate_interval])) * 60", "{{event}}")], "short",
            links=[traces_link("banking-service")])
    b.panel("Oldest waiting approval", "Seconds the oldest request has waited.",
            [("max(freightflow_approvals_pending_oldest_age_seconds)", "oldest")], "s",
            links=[logs_link("banking-service")])
    b.panel("Parked payment commands", "Commands out of attempts, waiting for an operator.",
            [("max(freightflow_payment_commands_parked)", "parked")], "short", kind="stat", width=8,
            links=[logs_link("logistics-service")], thresholds=[(1, "red")])
    return b


def kafka() -> Board:
    b = Board("kafka", "Kafka", "Lag, retries and dead letters (ADR 0022, ADR 0026).", ["kafka"])
    b.row("Consumers")
    b.panel("Consumer lag by group", "Records behind on each topic.",
            [("sum by (group, topic) (kafka_consumer_group_lag)", "{{group}} {{topic}}")], "short",
            thresholds=[(100, "red")])
    b.panel("Lag by partition", "A single stuck partition shows here first.",
            [("max by (group, topic, partition) (kafka_consumer_group_lag)", "{{group}} {{topic}}/{{partition}}")],
            "short")
    b.panel("Dead letters waiting", "Per service, waiting for an operator to redrive.",
            [('sum by (service) (freightflow_dead_letters{status="NEW"})', "{{service}}")], "short",
            links=[logs_link(None, ' |= "dead letter"')], thresholds=[(1, "orange")])
    b.panel("Events dead-lettered", "Per minute, by topic.",
            [("sum by (service, topic) (rate(freightflow_events_dead_lettered_total[$__rate_interval])) * 60",
              "{{service}} {{topic}}")], "short", links=[logs_link()])
    b.row("Producers")
    b.panel("Published per minute", "Acknowledged by the broker, by topic and type.",
            [("sum by (topic, type) (rate(freightflow_outbox_published_total[$__rate_interval])) * 60",
              "{{topic}} {{type}}")], "short")
    b.panel("Send failures", "Sends the broker did not acknowledge; retried with backoff.",
            [("sum by (service, topic) (rate(freightflow_outbox_send_failures_total[$__rate_interval])) * 60",
              "{{service}} {{topic}}")], "short", links=[logs_link(None, ' |= "not acknowledged"')])
    b.panel("Kafka client record send rate", "From the producers' own metrics.",
            [("sum by (service) (kafka_producer_record_send_rate)", "{{service}}")], "short")
    b.panel("Topic partitions and offsets", "The collector's view of each topic.",
            [("sum by (topic) (kafka_topic_partitions)", "{{topic}}")], "short")
    return b


def runtime() -> Board:
    b = Board("runtime", "Runtime", "JVMs, connection pools, Postgres, Redis, Keycloak and each container's memory "
              "against its limit (ADR 0029).", ["runtime"])
    b.row("Containers")
    b.panel("Memory against limit", "Each container's memory as a share of its limit (ADR 0029).",
            [("max by (container_name) (container_memory_usage_total_bytes / container_memory_usage_limit_bytes)",
              "{{container_name}}")], "percentunit", width=24, thresholds=[(0.8, "orange"), (0.9, "red")])
    b.row("JVM")
    b.panel("Heap used", "By service.",
            [('sum by (service) (jvm_memory_used_bytes{area="heap"})', "{{service}}")], "bytes")
    b.panel("Non-heap used", "Metaspace, code cache, compressed class space.",
            [('sum by (service) (jvm_memory_used_bytes{area="nonheap"})', "{{service}}")], "bytes")
    b.panel("GC pause time share", "Seconds paused per second.",
            [("sum by (service) (rate(jvm_gc_pause_seconds_sum[$__rate_interval]))", "{{service}}")], "percentunit")
    b.panel("Live threads", "By service.", [("max by (service) (jvm_threads_live_threads)", "{{service}}")], "short")
    b.row("Pools and stores")
    b.panel("Hikari connections in use", "Active against the pool's maximum.",
            [("max by (service) (hikaricp_connections_active)", "active {{service}}"),
             ("max by (service) (hikaricp_connections_max)", "max {{service}}")], "short")
    b.panel("Hikari wait p95", "Time to get a connection.",
            [("histogram_quantile(0.95, sum by (le, service) (rate(hikaricp_connections_acquire_seconds_bucket[$__rate_interval])))",
              "{{service}}"), ("max by (service) (hikaricp_connections_pending)", "pending {{service}}")], "s")
    b.panel("Postgres backends", "Connections per database (the collector's postgresql receiver).",
            [("sum by (postgresql_database_name) (postgresql_backends)", "{{postgresql_database_name}}")], "short")
    b.panel("Postgres database size", "Bytes per database.",
            [("max by (postgresql_database_name) (postgresql_db_size_bytes)", "{{postgresql_database_name}}")],
            "bytes")
    b.panel("Redis memory and clients", "The gateway's rate-limit buckets (ADR 0017).",
            [("max(redis_memory_used_bytes)", "memory"), ("max(redis_clients_connected)", "clients")], "short")
    b.panel("Keycloak logins and token requests", "From Keycloak's own metrics.",
            [("sum by (outcome) (rate(http_server_requests_seconds_count{service=\"keycloak\"}[$__rate_interval]))",
              "{{outcome}}")], "reqps", links=[logs_link("keycloak")])
    return b


def main() -> None:
    OUT.mkdir(exist_ok=True)
    boards = [red(s) for s in SERVICES] + [flow(), money(), kafka(), runtime()]
    for board in boards:
        (OUT / f"{board.uid}.json").write_bytes((json.dumps(board.json(), indent=2) + "\n").encode())
    print(f"wrote {len(boards)} dashboards to {OUT}")


if __name__ == "__main__":
    main()
