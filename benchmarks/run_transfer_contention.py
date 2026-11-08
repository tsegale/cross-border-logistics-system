#!/usr/bin/env python3
"""
Benchmarks `pessimistic` (SELECT ... FOR UPDATE) vs `conditional-update`
(UPDATE accounts SET balance = balance - :x WHERE id = :id AND balance >= :x)
across two workloads:
  1. `hot_account`:   100 concurrent workers draining 1 hot source account
  2. `many_accounts`: 100 concurrent workers transferring across 100 accounts

Runs 3 interleaved rounds per workload (rotating strategy order across rounds to
eliminate warm-up bias) through the API gateway (:8080) and verifies
`GET /api/v1/ledger/reconciliation` reports `balanced == true` after every run.
"""

from __future__ import annotations

import concurrent.futures
import http.client
import json
import math
import os
import platform
import statistics
import subprocess
import threading
import time
import urllib.parse
import uuid
from pathlib import Path
from typing import Any

BASE_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
CONCURRENCY = int(os.environ.get("BENCH_VUS", "100"))
REQUESTS_PER_RUN = int(os.environ.get("BENCH_REQUESTS_PER_RUN", "1000"))
ROUNDS = int(os.environ.get("BENCH_ROUNDS", "3"))

_tls = threading.local()


def _get_conn(parsed_url: urllib.parse.ParseResult) -> http.client.HTTPConnection:
    conn = getattr(_tls, "conn", None)
    if conn is None:
        conn = http.client.HTTPConnection(parsed_url.hostname, parsed_url.port or 80, timeout=30)
        _tls.conn = conn
    return conn


def _request_json(
    parsed_url: urllib.parse.ParseResult,
    method: str,
    path: str,
    body: dict[str, Any] | None = None,
    headers: dict[str, str] | None = None,
) -> tuple[int, dict[str, Any]]:
    req_headers = {"Accept": "application/json", "Connection": "keep-alive"}
    if headers:
        req_headers.update(headers)
    payload = None
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        req_headers["Content-Type"] = "application/json"

    for attempt in range(2):
        conn = _get_conn(parsed_url)
        try:
            conn.request(method, path, body=payload, headers=req_headers)
            resp = conn.getresponse()
            raw = resp.read()
            data = json.loads(raw.decode("utf-8")) if raw else {}
            return resp.status, data
        except Exception:
            _tls.conn = None
            if attempt == 1:
                raise
    raise RuntimeError("Unreachable")


def create_and_fund_account(
    parsed_url: urllib.parse.ParseResult, holder_reference: str, deposit_amount: str
) -> str:
    status, acc = _request_json(
        parsed_url,
        "POST",
        "/api/v1/accounts",
        {"holderReference": holder_reference, "currency": "EUR"},
    )
    if status != 201:
        raise RuntimeError(f"Failed to create account ({status}): {acc}")

    if float(deposit_amount) > 0:
        dep_status, dep = _request_json(
            parsed_url,
            "POST",
            f"/api/v1/accounts/{acc['id']}/deposits",
            {
                "amount": deposit_amount,
                "currency": "EUR",
                "reference": f"BENCH-FUND-{acc['accountNumber']}",
            },
            {"Idempotency-Key": f"fund-{acc['id']}"},
        )
        if dep_status != 201:
            raise RuntimeError(f"Failed to fund account ({dep_status}): {dep}")
    return acc["accountNumber"]


def percentile(sorted_values: list[float], pct: float) -> float:
    if not sorted_values:
        return 0.0
    idx = (len(sorted_values) - 1) * (pct / 100.0)
    lo = math.floor(idx)
    hi = math.ceil(idx)
    if lo == hi:
        return sorted_values[lo]
    return sorted_values[lo] + (sorted_values[hi] - sorted_values[lo]) * (idx - lo)


def setup_workload_accounts(
    parsed_url: urllib.parse.ParseResult, workload: str
) -> tuple[str | None, list[str]]:
    if workload == "hot_account":
        hot_source = create_and_fund_account(parsed_url, "Bench Hot Source", "50000000.00")
        with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
            sinks = list(
                pool.map(
                    lambda i: create_and_fund_account(parsed_url, f"Bench Sink {i}", "0.00"),
                    range(CONCURRENCY),
                )
            )
        return hot_source, sinks

    with concurrent.futures.ThreadPoolExecutor(max_workers=20) as pool:
        accounts = list(
            pool.map(
                lambda i: create_and_fund_account(parsed_url, f"Bench Trader {i}", "5000000.00"),
                range(CONCURRENCY),
            )
        )
    return None, accounts


def run_single_benchmark(
    parsed_url: urllib.parse.ParseResult,
    workload: str,
    strategy: str,
    round_num: int,
    hot_source: str | None,
    accounts: list[str],
) -> dict[str, Any]:
    run_id = uuid.uuid4().hex[:8]
    barrier = threading.Barrier(CONCURRENCY)

    def worker_task(req_idx: int) -> tuple[int, float]:
        vu_idx = req_idx % CONCURRENCY
        if req_idx < CONCURRENCY:
            try:
                barrier.wait(timeout=10.0)
            except threading.BrokenBarrierError:
                pass
        if workload == "hot_account":
            src = hot_source
            dst = accounts[vu_idx]
        else:
            src = accounts[vu_idx]
            dst = accounts[(vu_idx + 1) % len(accounts)]

        idem_key = f"bench-{workload}-{strategy}-r{round_num}-{run_id}-{req_idx}"
        t0 = time.perf_counter()
        status, _ = _request_json(
            parsed_url,
            "POST",
            f"/api/v1/transfers?strategy={strategy}",
            {
                "sourceAccount": src,
                "targetAccount": dst,
                "amount": "1.00",
                "currency": "EUR",
                "reference": f"BENCH-{strategy.upper()}",
            },
            {"Idempotency-Key": idem_key},
        )
        elapsed_ms = (time.perf_counter() - t0) * 1000.0
        return status, elapsed_ms

    start_wall = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=CONCURRENCY) as executor:
        results = list(executor.map(worker_task, range(REQUESTS_PER_RUN)))
    wall_seconds = time.perf_counter() - start_wall

    latencies = sorted(r[1] for r in results)
    statuses = [r[0] for r in results]
    ok_count = sum(1 for s in statuses if s == 201)
    err_count = len(statuses) - ok_count

    rec_status, rec_body = _request_json(parsed_url, "GET", "/api/v1/ledger/reconciliation")
    balanced = rec_status == 200 and rec_body.get("balanced") is True
    if not balanced:
        raise RuntimeError(f"Reconciliation failed after {workload}/{strategy}: {rec_body}")

    metrics = {
        "round": round_num,
        "workload": workload,
        "strategy": strategy,
        "requests": REQUESTS_PER_RUN,
        "concurrency": CONCURRENCY,
        "ok_201": ok_count,
        "errors": err_count,
        "wall_s": round(wall_seconds, 3),
        "rps": round(REQUESTS_PER_RUN / wall_seconds, 2),
        "p50_ms": round(percentile(latencies, 50), 2),
        "p95_ms": round(percentile(latencies, 95), 2),
        "p99_ms": round(percentile(latencies, 99), 2),
        "mean_ms": round(statistics.mean(latencies), 2),
        "reconciled": balanced,
    }
    print(
        f"  [Round {round_num}] {workload:13s} | {strategy:18s} -> "
        f"{metrics['rps']:7.2f} req/s | p50={metrics['p50_ms']:6.2f}ms | "
        f"p95={metrics['p95_ms']:6.2f}ms | p99={metrics['p99_ms']:6.2f}ms | "
        f"errors={err_count} | reconciled={balanced}"
    )
    return metrics


def detect_cpu_name() -> str:
    if platform.system() == "Windows":
        try:
            out = subprocess.check_output(
                [
                    "powershell",
                    "-NoProfile",
                    "-Command",
                    "(Get-CimInstance Win32_Processor).Name",
                ],
                text=True,
                timeout=5,
            ).strip()
            if out:
                return out
        except Exception:
            pass
    return platform.processor() or platform.machine()


def main() -> None:
    parsed_url = urllib.parse.urlsplit(BASE_URL)
    print(f"Running transfer contention benchmark against {BASE_URL} "
          f"(concurrency={CONCURRENCY}, requests/run={REQUESTS_PER_RUN}, rounds={ROUNDS})")

    # Warm up JVM & connection pools
    print("Warming up banking-service and api-gateway...")
    warm_src, warm_sinks = setup_workload_accounts(parsed_url, "hot_account")
    run_single_benchmark(parsed_url, "hot_account", "pessimistic", 0, warm_src, warm_sinks)
    run_single_benchmark(parsed_url, "hot_account", "conditional-update", 0, warm_src, warm_sinks)

    all_runs: list[dict[str, Any]] = []
    for workload in ("hot_account", "many_accounts"):
        print(f"\nSetting up accounts for workload: {workload}...")
        hot_source, accounts = setup_workload_accounts(parsed_url, workload)
        for r in range(1, ROUNDS + 1):
            order = (
                ("pessimistic", "conditional-update")
                if r % 2 == 1
                else ("conditional-update", "pessimistic")
            )
            for strategy in order:
                res = run_single_benchmark(
                    parsed_url, workload, strategy, r, hot_source, accounts
                )
                all_runs.append(res)

    summary: dict[str, dict[str, Any]] = {}
    for workload in ("hot_account", "many_accounts"):
        summary[workload] = {}
        for strategy in ("pessimistic", "conditional-update"):
            subset = [
                m for m in all_runs if m["workload"] == workload and m["strategy"] == strategy
            ]
            summary[workload][strategy] = {
                "median_rps": round(statistics.median(m["rps"] for m in subset), 2),
                "median_p50_ms": round(statistics.median(m["p50_ms"] for m in subset), 2),
                "median_p95_ms": round(statistics.median(m["p95_ms"] for m in subset), 2),
                "median_p99_ms": round(statistics.median(m["p99_ms"] for m in subset), 2),
                "total_errors": sum(m["errors"] for m in subset),
                "all_reconciled": all(m["reconciled"] for m in subset),
                "rounds": subset,
            }

    out_dir = Path(__file__).resolve().parent / "out"
    out_dir.mkdir(parents=True, exist_ok=True)
    out_file = out_dir / "transfer_contention_results.json"
    payload = {
        "machine": {
            "cpu": detect_cpu_name(),
            "os": f"{platform.system()} {platform.release()} ({platform.version()})",
            "cores_logical": os.cpu_count(),
            "python": platform.python_version(),
        },
        "config": {
            "base_url": BASE_URL,
            "concurrency": CONCURRENCY,
            "requests_per_run": REQUESTS_PER_RUN,
            "rounds": ROUNDS,
        },
        "summary": summary,
    }
    out_file.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    print(f"\nSaved benchmark results to {out_file}")
    print(json.dumps(payload["summary"], indent=2))


if __name__ == "__main__":
    main()
