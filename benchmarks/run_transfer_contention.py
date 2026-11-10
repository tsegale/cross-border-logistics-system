#!/usr/bin/env python3
"""
Transfer contention benchmark: `pessimistic` vs `conditional-update` (ADR 0005).

The locking strategy is a startup property of banking-service, not a request parameter. For every measured
block this script recreates the banking-service container with TRANSFER_LOCKING_STRATEGY set, waits until
GET /actuator/info reports that strategy, warms the JVM up, and only then measures. k6
(benchmarks/k6/transfer_contention.js) generates the load; this script orchestrates and collects diagnostics:

  * server-side latency from /actuator/prometheus (http_server_requests), so gateway and client overhead can
    be told apart from time spent in banking-service
  * HikariCP connection acquire and usage time, which separates "waiting for a connection" from "holding one"
  * pg_stat_statements (enabled by benchmarks/compose.bench.yml), the database time per statement
  * docker stats CPU samples while the run is in flight

Workloads:
  baseline        1 VU, disjoint pair: the uncontended latency of one transfer
  disjoint_pairs  N VUs, VU i always transfers pairs[i][0] -> pairs[i][1]; no account is shared by two VUs
  hot_account     N VUs all debit one source account into their own sink account

Rounds alternate the strategy order (A B, B A, A B) so neither strategy always runs on the warmer database.
After every run GET /api/v1/ledger/reconciliation must report balanced == true, or the script stops.

Run from the repository root with the stack up:
  docker compose -f docker-compose.yml -f benchmarks/compose.bench.yml up -d --build --wait
  python benchmarks/run_transfer_contention.py
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import platform
import re
import statistics
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent.parent
OUT_DIR = Path(__file__).resolve().parent / "out"
K6_SCRIPT = Path(__file__).resolve().parent / "k6" / "transfer_contention.js"

GATEWAY_URL = os.environ.get("BASE_URL", "http://127.0.0.1:8080")
BANKING_URL = os.environ.get("BANKING_URL", "http://127.0.0.1:8081")
ROUNDS = int(os.environ.get("BENCH_ROUNDS", "3"))
VU_LEVELS = [int(v) for v in os.environ.get("BENCH_VUS", "32,100").split(",")]
REQUESTS_PER_RUN = int(os.environ.get("BENCH_REQUESTS_PER_RUN", "1000"))
BASELINE_REQUESTS = int(os.environ.get("BENCH_BASELINE_REQUESTS", "300"))
WARMUP_REQUESTS = int(os.environ.get("BENCH_WARMUP_REQUESTS", "1500"))
STRATEGIES = os.environ.get("BENCH_STRATEGIES", "pessimistic,conditional-update").split(",")
WORKLOADS = os.environ.get("BENCH_WORKLOADS", "disjoint_pairs,hot_account").split(",")
COMPOSE = ["docker", "compose", "-f", str(ROOT / "docker-compose.yml"), "-f", str(ROOT / "benchmarks" / "compose.bench.yml")]


# ---------------------------------------------------------------------------- HTTP helpers

def http_json(method: str, url: str, body: dict[str, Any] | None = None,
              headers: dict[str, str] | None = None, timeout: float = 30) -> tuple[int, Any]:
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method)
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", "application/json")
    for k, v in (headers or {}).items():
        req.add_header(k, v)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return resp.status, json.loads(raw) if raw else None
    except urllib.error.HTTPError as err:
        raw = err.read()
        try:
            return err.code, json.loads(raw) if raw else None
        except json.JSONDecodeError:
            return err.code, raw.decode(errors="replace")


def http_text(url: str) -> str:
    with urllib.request.urlopen(url, timeout=30) as resp:
        return resp.read().decode()


# ---------------------------------------------------------------------------- stack control

def compose(*args: str, env: dict[str, str] | None = None, capture: bool = False) -> str:
    result = subprocess.run([*COMPOSE, *args], cwd=ROOT, env={**os.environ, **(env or {})},
                            check=True, text=True, capture_output=capture)
    return result.stdout if capture else ""


def psql(sql: str) -> str:
    return compose("exec", "-T", "banking-db", "sh", "-c",
                   f'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At -F "|" -c "{sql}"', capture=True)


def restart_banking(strategy: str) -> None:
    print(f"\n==> Recreating banking-service with TRANSFER_LOCKING_STRATEGY={strategy}")
    compose("up", "-d", "--force-recreate", "--wait", "banking-service",
            env={"TRANSFER_LOCKING_STRATEGY": strategy})
    deadline = time.time() + 120
    while time.time() < deadline:
        try:
            status, info = http_json("GET", f"{BANKING_URL}/actuator/info", timeout=5)
            if status == 200:
                active = info.get("transfer", {}).get("lockingStrategy")
                if active != strategy:
                    raise RuntimeError(f"banking-service reports strategy {active!r}, expected {strategy!r}")
                print(f"    /actuator/info reports lockingStrategy={active}")
                return
        except (urllib.error.URLError, ConnectionError, TimeoutError):
            pass
        time.sleep(1)
    raise RuntimeError("banking-service did not come back within 120 s")


# ---------------------------------------------------------------------------- accounts

def create_account(holder: str, deposit: str) -> str:
    status, acc = http_json("POST", f"{GATEWAY_URL}/api/v1/accounts", {"holderReference": holder, "currency": "EUR"})
    if status != 201:
        raise RuntimeError(f"create account failed ({status}): {acc}")
    if deposit != "0":
        status, dep = http_json("POST", f"{GATEWAY_URL}/api/v1/accounts/{acc['id']}/deposits",
                                {"amount": deposit, "currency": "EUR", "reference": f"BENCH-FUND-{acc['accountNumber']}"},
                                {"Idempotency-Key": f"bench-fund-{acc['id']}"})
        if status != 201:
            raise RuntimeError(f"deposit failed ({status}): {dep}")
    return acc["accountNumber"]


def setup_accounts(max_vus: int) -> Path:
    print(f"==> Creating accounts for up to {max_vus} VUs")
    with concurrent.futures.ThreadPoolExecutor(max_workers=16) as pool:
        hot_source = create_account("Bench Hot Source", "90000000.00")
        sinks = list(pool.map(lambda i: create_account(f"Bench Sink {i}", "0"), range(max_vus)))
        sources = list(pool.map(lambda i: create_account(f"Bench Pair Source {i}", "1000000.00"), range(max_vus)))
        targets = list(pool.map(lambda i: create_account(f"Bench Pair Target {i}", "0"), range(max_vus)))
    accounts = {"hotSource": hot_source, "sinks": sinks, "pairs": [list(p) for p in zip(sources, targets)]}
    flat = [hot_source, *sinks, *sources, *targets]
    if len(set(flat)) != len(flat):
        raise RuntimeError("account numbers are not unique")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    path = OUT_DIR / "accounts.json"
    path.write_text(json.dumps(accounts, indent=2), encoding="utf-8")
    return path


# ---------------------------------------------------------------------------- diagnostics

PROM_LINE = re.compile(r'^(?P<name>[a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{(?P<labels>[^}]*)\})?\s+(?P<value>\S+)$')


def scrape_prometheus() -> dict[str, float]:
    """Sums the counters this report needs. Keys are metric names, filtered by labels where it matters."""
    wanted: dict[str, float] = {}
    for line in http_text(f"{BANKING_URL}/actuator/prometheus").splitlines():
        m = PROM_LINE.match(line)
        if not m:
            continue
        name, labels, value = m.group("name"), m.group("labels") or "", float(m.group("value"))
        if name.startswith("http_server_requests_seconds_") and 'uri="/api/v1/transfers"' in labels and 'method="POST"' in labels:
            key = name
        elif name in ("hikaricp_connections_acquire_seconds_sum", "hikaricp_connections_acquire_seconds_count",
                      "hikaricp_connections_usage_seconds_sum", "hikaricp_connections_usage_seconds_count"):
            key = name
        else:
            continue
        if key.endswith("_max"):
            continue
        wanted[key] = wanted.get(key, 0.0) + value
    return wanted


def mean_ms(after: dict[str, float], before: dict[str, float], prefix: str) -> float | None:
    count = after.get(f"{prefix}_count", 0) - before.get(f"{prefix}_count", 0)
    total = after.get(f"{prefix}_sum", 0) - before.get(f"{prefix}_sum", 0)
    return round(total / count * 1000, 2) if count > 0 else None


def reset_pg_stat_statements() -> None:
    psql("CREATE EXTENSION IF NOT EXISTS pg_stat_statements")
    psql("SELECT pg_stat_statements_reset()")


def top_statements(limit: int = 8) -> list[dict[str, Any]]:
    rows = psql(
        "SELECT calls, round(total_exec_time::numeric, 1), round(mean_exec_time::numeric, 3), "
        "regexp_replace(left(query, 110), '\\s+', ' ', 'g') FROM pg_stat_statements "
        f"WHERE query NOT ILIKE '%pg_stat_statements%' ORDER BY total_exec_time DESC LIMIT {limit}")
    out = []
    for row in rows.strip().splitlines():
        calls, total, mean, query = row.split("|", 3)
        out.append({"calls": int(calls), "total_ms": float(total), "mean_ms": float(mean), "query": query})
    return out


class CpuSampler(threading.Thread):
    """Samples `docker stats` while a run is in flight; reports the mean CPU % per container."""

    def __init__(self) -> None:
        super().__init__(daemon=True)
        self.samples: dict[str, list[float]] = {}
        self._stop = threading.Event()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}|{{.CPUPerc}}"],
                                     capture_output=True, text=True, timeout=20).stdout
                for line in out.strip().splitlines():
                    name, cpu = line.split("|")
                    if name.startswith("freightflow-"):
                        self.samples.setdefault(name.removeprefix("freightflow-").rsplit("-", 1)[0], []).append(
                            float(cpu.rstrip("%")))
            except (subprocess.SubprocessError, ValueError):
                pass

    def stop(self) -> dict[str, float]:
        self._stop.set()
        self.join(timeout=30)
        return {k: round(statistics.mean(v), 1) for k, v in self.samples.items() if v}


# ---------------------------------------------------------------------------- one k6 run

def run_k6(accounts_file: Path, workload: str, vus: int, iterations_per_vu: int, label: str) -> dict[str, Any]:
    summary_file = OUT_DIR / f"k6-{label}.json"
    subprocess.run(
        ["k6", "run", "--quiet", "--no-color",
         "-e", f"BASE_URL={GATEWAY_URL}", "-e", f"ACCOUNTS_FILE={accounts_file}", "-e", f"WORKLOAD={workload}",
         "-e", f"VUS={vus}", "-e", f"ITERATIONS_PER_VU={iterations_per_vu}", "-e", f"RUN_TAG={uuid.uuid4().hex[:12]}",
         "-e", f"SUMMARY_FILE={summary_file}", str(K6_SCRIPT)],
        cwd=ROOT, check=True, capture_output=True, text=True)
    return json.loads(summary_file.read_text(encoding="utf-8"))


def counter(summary: dict[str, Any], name: str) -> int:
    return int(summary["metrics"].get(name, {}).get("values", {}).get("count", 0))


def measured_run(accounts_file: Path, strategy: str, workload: str, vus: int, requests: int,
                 round_num: int) -> dict[str, Any]:
    iterations = max(1, requests // vus)
    reset_pg_stat_statements()
    before = scrape_prometheus()
    sampler = CpuSampler()
    sampler.start()
    started = time.perf_counter()
    # baseline is the disjoint-pairs workload with a single VU
    k6_workload = "disjoint_pairs" if workload == "baseline" else workload
    summary = run_k6(accounts_file, k6_workload, vus, iterations, f"r{round_num}-{strategy}-{workload}-{vus}")
    wall = time.perf_counter() - started
    cpu = sampler.stop()
    after = scrape_prometheus()
    statements = top_statements()

    status, recon = http_json("GET", f"{GATEWAY_URL}/api/v1/ledger/reconciliation", timeout=120)
    if status != 200 or recon.get("balanced") is not True:
        raise RuntimeError(f"reconciliation failed after {strategy}/{workload}/{vus}: {recon}")

    duration = summary["metrics"]["http_req_duration"]["values"]
    created = counter(summary, "transfers_created")
    run = {
        "round": round_num,
        "strategy": strategy,
        "workload": workload,
        "vus": vus,
        "requests": iterations * vus,
        "created_201": created,
        "conflict_409": counter(summary, "transfers_409"),
        "shed_503": counter(summary, "transfers_503"),
        "other": counter(summary, "transfers_other"),
        "rps": round(summary["metrics"]["http_reqs"]["values"]["rate"], 2),
        "created_per_s": round(created / summary["state"]["testRunDurationMs"] * 1000, 2),
        "p50_ms": round(duration["med"], 2),
        "p95_ms": round(duration["p(95)"], 2),
        "p99_ms": round(duration["p(99)"], 2),
        "server_mean_ms": mean_ms(after, before, "http_server_requests_seconds"),
        "hikari_acquire_mean_ms": mean_ms(after, before, "hikaricp_connections_acquire_seconds"),
        "hikari_usage_mean_ms": mean_ms(after, before, "hikaricp_connections_usage_seconds"),
        "cpu_percent": cpu,
        "top_statements": statements,
        "reconciled": True,
        "wall_s": round(wall, 1),
    }
    print(f"  r{round_num} {strategy:18s} {workload:14s} vus={vus:3d} -> {run['rps']:7.2f} req/s "
          f"p50={run['p50_ms']:8.2f} p95={run['p95_ms']:8.2f} p99={run['p99_ms']:8.2f} "
          f"server={run['server_mean_ms']} acquire={run['hikari_acquire_mean_ms']} usage={run['hikari_usage_mean_ms']} "
          f"201={created} 409={run['conflict_409']} 503={run['shed_503']} cpu={cpu}")
    return run


# ---------------------------------------------------------------------------- report

def machine() -> dict[str, Any]:
    cpu = platform.processor() or platform.machine()
    if platform.system() == "Windows":
        try:
            cpu = subprocess.check_output(["powershell", "-NoProfile", "-Command",
                                           "(Get-CimInstance Win32_Processor).Name"], text=True, timeout=10).strip()
        except (subprocess.SubprocessError, OSError):
            pass
    docker = subprocess.run(["docker", "info", "--format", "{{.ServerVersion}}|{{.NCPU}}|{{.MemTotal}}"],
                            capture_output=True, text=True).stdout.strip().split("|")
    return {
        "cpu": cpu,
        "logical_cores": os.cpu_count(),
        "os": f"{platform.system()} {platform.release()} ({platform.version()})",
        "docker_server": docker[0] if docker else None,
        "docker_cpus": int(docker[1]) if len(docker) > 1 else None,
        "docker_mem_gib": round(int(docker[2]) / 2**30, 1) if len(docker) > 2 else None,
        "k6": subprocess.run(["k6", "version"], capture_output=True, text=True).stdout.strip().splitlines()[0],
    }


def summarise(runs: list[dict[str, Any]]) -> list[dict[str, Any]]:
    keys = sorted({(r["workload"], r["vus"], r["strategy"]) for r in runs},
                  key=lambda k: (k[0], k[1], STRATEGIES.index(k[2])))
    rows = []
    for workload, vus, strategy in keys:
        subset = [r for r in runs if (r["workload"], r["vus"], r["strategy"]) == (workload, vus, strategy)]
        rps = [r["rps"] for r in subset]
        rows.append({
            "workload": workload, "vus": vus, "strategy": strategy, "rounds": len(subset),
            "median_rps": round(statistics.median(rps), 2),
            "min_rps": min(rps), "max_rps": max(rps),
            "median_p50_ms": round(statistics.median(r["p50_ms"] for r in subset), 2),
            "median_p95_ms": round(statistics.median(r["p95_ms"] for r in subset), 2),
            "median_p99_ms": round(statistics.median(r["p99_ms"] for r in subset), 2),
            "median_server_mean_ms": round(statistics.median(r["server_mean_ms"] or 0 for r in subset), 2),
            "total_201": sum(r["created_201"] for r in subset),
            "total_503": sum(r["shed_503"] for r in subset),
            "total_409": sum(r["conflict_409"] for r in subset),
        })
    return rows


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Transfer contention benchmark: strategies={STRATEGIES} workloads={WORKLOADS} vus={VU_LEVELS} "
          f"rounds={ROUNDS} requests/run={REQUESTS_PER_RUN}")
    restart_banking(STRATEGIES[0])
    accounts_file = setup_accounts(max(VU_LEVELS))

    runs: list[dict[str, Any]] = []
    for round_num in range(1, ROUNDS + 1):
        order = STRATEGIES if round_num % 2 == 1 else list(reversed(STRATEGIES))
        for strategy in order:
            restart_banking(strategy)
            print(f"    warm-up: {WARMUP_REQUESTS} requests (discarded)")
            run_k6(accounts_file, "disjoint_pairs", 32, max(1, WARMUP_REQUESTS // 32), f"warmup-r{round_num}-{strategy}")
            runs.append(measured_run(accounts_file, strategy, "baseline", 1, BASELINE_REQUESTS, round_num))
            for workload in WORKLOADS:
                for vus in VU_LEVELS:
                    runs.append(measured_run(accounts_file, strategy, workload, vus, REQUESTS_PER_RUN, round_num))

    result = {"machine": machine(),
              "config": {"rounds": ROUNDS, "vu_levels": VU_LEVELS, "requests_per_run": REQUESTS_PER_RUN,
                         "baseline_requests": BASELINE_REQUESTS, "warmup_requests": WARMUP_REQUESTS,
                         "gateway": GATEWAY_URL, "banking": BANKING_URL},
              "summary": summarise(runs), "runs": runs}
    out = OUT_DIR / "transfer_contention_results.json"
    out.write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(f"\nSaved {out}")
    for row in result["summary"]:
        print(row)


if __name__ == "__main__":
    main()
