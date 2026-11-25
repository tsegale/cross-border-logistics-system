#!/usr/bin/env python3
"""Container and JVM memory peaks while the stack works (ADR 0029).

Samples, until it is stopped (SIGTERM or SIGINT, or the file given by --stop-file appears):

- every container's memory as `docker stats` reports it (the cgroup's usage without its inactive page cache, which
  is what the kernel compares with a container's limit), every --interval seconds;
- every Spring service's JVM from its own /actuator/prometheus, read inside its container (nothing is published):
  heap used and committed, non-heap used (metaspace, code cache, compressed class space), live threads, and its
  garbage collector's pause count, total and longest pause.

Writes the peaks (and each JVM's GC totals at the end) as JSON and prints them as a Markdown table. Standard library
only.

    python3 benchmarks/memory_peaks.py --json out/memory.json &   # ... run the workload, then kill %1
"""
from __future__ import annotations

import argparse
import json
import os
import re
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path

COMPOSE = os.environ.get("COMPOSE", "docker compose").split()
PROJECT = os.environ.get("COMPOSE_PROJECT_NAME", "freightflow")
# Service, and its actuator's port inside the container (the gateway's management port).
JVMS = {"banking-service": 8081, "logistics-service": 8082, "notification-service": 8083, "api-gateway": 8085}
UNITS = {"B": 1, "KiB": 1024, "MiB": 1024 ** 2, "GiB": 1024 ** 3, "kB": 1000, "MB": 1000 ** 2, "GB": 1000 ** 3}
SAMPLE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})?\s+(\S+)')

stop = threading.Event()


def mib(text: str) -> float:
    match = re.match(r"([\d.]+)\s*([A-Za-z]+)", text.strip())
    if not match:
        raise ValueError(f"not a size: {text!r}")
    return float(match.group(1)) * UNITS[match.group(2)] / 1024 ** 2


def service_of(container: str) -> str:
    # freightflow-banking-service-1 -> banking-service
    name = container.removeprefix(PROJECT + "-")
    return re.sub(r"-\d+$", "", name)


LIMITS: dict[str, float] = {}


def containers() -> dict[str, float]:
    out = subprocess.run(["docker", "stats", "--no-stream", "--format", "{{.Name}}\t{{.MemUsage}}"],
                         capture_output=True, text=True, check=True).stdout
    usage = {}
    for line in out.splitlines():
        name, _, mem = line.partition("\t")
        if name and mem:
            used, _, limit = mem.partition("/")
            usage[service_of(name)] = mib(used)
            # The container's limit, or the host's memory when it has none.
            LIMITS[service_of(name)] = mib(limit)
    return usage


def actuator_metrics(service: str, port: int) -> subprocess.CompletedProcess:
    # TLS everywhere, verified inside the container against its own copy of the internal root (ADR 0039); the services
    # take mutual TLS and accept their own certificate (ADR 0040), the gateway's management port asks for none.
    return subprocess.run([*COMPOSE, "exec", "-T", service, "curl", "-fsS", "--cacert", "/tls/ca.pem",
                           "--cert", "/tls/cert.pem", "--key", "/tls/key.pem",
                           f"https://localhost:{port}/actuator/prometheus"],
                          capture_output=True, text=True, timeout=20)


def scrape(service: str, port: int) -> dict[str, float]:
    result = actuator_metrics(service, port)
    if result.returncode != 0:
        return {}
    values: dict[str, float] = {"heap_used": 0.0, "heap_committed": 0.0, "nonheap_used": 0.0, "metaspace_used": 0.0,
                                "threads": 0.0, "gc_pauses": 0.0, "gc_pause_seconds": 0.0, "gc_pause_max_ms": 0.0}
    for line in result.stdout.splitlines():
        match = SAMPLE.match(line)
        if not match:
            continue
        name, labels, value = match.group(1), match.group(2) or "", float(match.group(3))
        if name == "jvm_memory_used_bytes":
            values["heap_used" if 'area="heap"' in labels else "nonheap_used"] += value / 1024 ** 2
            if 'id="Metaspace"' in labels:
                values["metaspace_used"] += value / 1024 ** 2
        elif name == "jvm_memory_committed_bytes" and 'area="heap"' in labels:
            values["heap_committed"] += value / 1024 ** 2
        elif name == "jvm_threads_live_threads":
            values["threads"] = value
        elif name == "jvm_gc_pause_seconds_count":
            values["gc_pauses"] += value
        elif name == "jvm_gc_pause_seconds_sum":
            values["gc_pause_seconds"] += value
        elif name == "jvm_gc_pause_seconds_max":
            values["gc_pause_max_ms"] = max(values["gc_pause_max_ms"], value * 1000)
    return values


def collectors(service: str, port: int) -> list[str]:
    result = actuator_metrics(service, port)
    names = set(re.findall(r'jvm_gc_pause_seconds_count\{[^}]*gc="([^"]+)"', result.stdout))
    return sorted(names)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--interval", type=float, default=2.0, help="seconds between container samples")
    parser.add_argument("--jvm-every", type=int, default=3, help="scrape the JVMs every n-th sample")
    parser.add_argument("--stop-file", help="stop when this file exists")
    parser.add_argument("--json", required=True, help="write the peaks here")
    args = parser.parse_args()

    for sig in (signal.SIGTERM, signal.SIGINT):
        signal.signal(sig, lambda *_: stop.set())

    peaks: dict[str, float] = {}
    jvm_peaks: dict[str, dict[str, float]] = {}
    last_jvm: dict[str, dict[str, float]] = {}
    samples = 0
    started = time.monotonic()
    while not stop.is_set():
        if args.stop_file and Path(args.stop_file).exists():
            break
        try:
            for service, used in containers().items():
                peaks[service] = max(peaks.get(service, 0.0), used)
        except (subprocess.CalledProcessError, ValueError) as error:
            print(f"memory_peaks: docker stats failed: {error}", file=sys.stderr)
        if samples % args.jvm_every == 0:
            for service, port in JVMS.items():
                try:
                    values = scrape(service, port)
                except subprocess.TimeoutExpired:
                    continue
                if not values:
                    continue
                last_jvm[service] = values
                peak = jvm_peaks.setdefault(service, {})
                for key, value in values.items():
                    peak[key] = max(peak.get(key, 0.0), value)
        samples += 1
        stop.wait(args.interval)

    gc = {service: collectors(service, port) for service, port in JVMS.items() if service in last_jvm}
    result = {
        "seconds": round(time.monotonic() - started, 1),
        "samples": samples,
        "containers_mib": {k: round(v, 1) for k, v in sorted(peaks.items())},
        "limits_mib": {k: round(v, 1) for k, v in sorted(LIMITS.items())},
        "total_mib": round(sum(peaks.values()), 1),
        "jvm_peaks": {s: {k: round(v, 1) for k, v in p.items()} for s, p in sorted(jvm_peaks.items())},
        "jvm_at_end": {s: {k: round(v, 1) for k, v in p.items()} for s, p in sorted(last_jvm.items())},
        "collectors": gc,
    }
    Path(args.json).parent.mkdir(parents=True, exist_ok=True)
    Path(args.json).write_text(json.dumps(result, indent=2) + "\n")

    print(f"{samples} samples over {result['seconds']} s\n")
    print("| Container | Peak MiB | Limit MiB | Peak of limit |")
    print("| --- | ---: | ---: | ---: |")
    for service, used in sorted(peaks.items(), key=lambda item: -item[1]):
        limit = LIMITS.get(service, 0)
        share = f"{100 * used / limit:.0f} %" if limit else ""
        print(f"| {service} | {used:.0f} | {limit:.0f} | {share} |")
    print(f"| **sum of peaks** | **{result['total_mib']:.0f}** | | |\n")
    print("| JVM | GC | Heap used peak | Heap committed peak | Non-heap peak | Metaspace peak | Threads peak |"
          " GC pauses | GC total s | Longest pause ms |")
    print("| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |")
    for service, peak in sorted(jvm_peaks.items()):
        end = last_jvm[service]
        print(f"| {service} | {', '.join(gc.get(service, []))} | {peak['heap_used']:.0f} | "
              f"{peak['heap_committed']:.0f} | {peak['nonheap_used']:.0f} | {peak['metaspace_used']:.0f} | "
              f"{peak['threads']:.0f} | {end['gc_pauses']:.0f} | {end['gc_pause_seconds']:.2f} | "
              f"{peak['gc_pause_max_ms']:.0f} |")


if __name__ == "__main__":
    main()
