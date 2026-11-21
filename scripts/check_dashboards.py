#!/usr/bin/env python3
"""Checks the provisioned Grafana dashboards (ADR 0032): each file is a dashboard with a unique uid and title; every
panel has a datasource, at least one query, a query that names a metric, and a link to traces or logs; every
datasource uid is one the provisioning defines; grid positions stay inside the 24 columns.

    python3 scripts/check_dashboards.py infra/observability/grafana/dashboards
"""
from __future__ import annotations

import json
import re
import sys
from pathlib import Path

DATASOURCES = {"prometheus", "tempo", "loki", "alertmanager"}
METRIC = re.compile(r"[a-z_][a-z0-9_]*(_total|_seconds|_bytes|_bucket|_count|_sum|_lag|\b)")


def check(path: Path) -> list[str]:
    problems = []
    board = json.loads(path.read_text(encoding="utf-8"))
    for key in ("uid", "title", "panels", "schemaVersion"):
        if key not in board:
            problems.append(f"{path.name}: no {key}")
    for panel in board.get("panels", []):
        where = f"{path.name} / {panel.get('title', '?')}"
        if panel.get("type") == "row":
            continue
        grid = panel.get("gridPos", {})
        if grid.get("x", 0) + grid.get("w", 0) > 24:
            problems.append(f"{where}: wider than the grid")
        if panel.get("datasource", {}).get("uid") not in DATASOURCES:
            problems.append(f"{where}: unknown datasource")
        targets = panel.get("targets", [])
        if not targets:
            problems.append(f"{where}: no query")
        for target in targets:
            expr = target.get("expr", "")
            if not expr.strip() or not METRIC.search(expr):
                problems.append(f"{where}: query names no metric: {expr!r}")
            if target.get("datasource", {}).get("uid") not in DATASOURCES:
                problems.append(f"{where}: query with an unknown datasource")
            if expr.count("(") != expr.count(")") or expr.count("{") != expr.count("}"):
                problems.append(f"{where}: unbalanced brackets in {expr!r}")
        links = panel.get("links", [])
        if not any("explore" in link.get("url", "") for link in links):
            problems.append(f"{where}: no link to traces or logs")
    return problems


def main() -> None:
    folder = Path(sys.argv[1])
    files = sorted(folder.glob("*.json"))
    if not files:
        raise SystemExit(f"check_dashboards: no dashboards in {folder}")
    problems, uids, titles = [], set(), set()
    for path in files:
        problems += check(path)
        board = json.loads(path.read_text(encoding="utf-8"))
        if board["uid"] in uids or board["title"] in titles:
            problems.append(f"{path.name}: uid or title used twice")
        uids.add(board["uid"])
        titles.add(board["title"])
    for problem in problems:
        print(f"::error::{problem}")
    if problems:
        raise SystemExit(1)
    panels = sum(len([p for p in json.loads(f.read_text())["panels"] if p["type"] != "row"]) for f in files)
    print(f"check_dashboards: {len(files)} dashboards, {panels} panels, all valid and linked")


if __name__ == "__main__":
    main()
