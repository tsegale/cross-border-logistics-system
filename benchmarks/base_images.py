"""banking-service on three runtime bases, compared (ADR 0047): Alpine with Temurin's JRE (today's), Google's
distroless Java 21 and Canonical's chiselled Ubuntu JRE 21.

Per base: the image size, Trivy's findings by severity (and how many have a fix), the OS packages, and, run in the
default stack in place of banking-service three times, Spring's own startup time, the container's memory after a
minute, and whether the gateway gets answers from it.

    docker compose up -d --wait            # the default stack, first
    python benchmarks/base_images.py --json base-images.json
"""
from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "benchmarks"))
import events_to_email as harness  # noqa: E402

TRIVY = "aquasec/trivy:0.76.1@sha256:790fd66d1697395d8693d1acaa1ea702406a20f109b6fc7bac8db623eca6c06c"
BASES = {
    "alpine": None,
    "distroless": ROOT / "benchmarks/base-images/Dockerfile.distroless",
    "chiselled": ROOT / "benchmarks/base-images/Dockerfile.chiselled",
}
# The JVM options compose gives banking (ADR 0029), for the bases without a shell to read JAVA_OPTS.
JVM = ("-XX:MaxRAMPercentage=45 -XX:InitialRAMPercentage=20 -XX:MaxMetaspaceSize=192m -Xss512k "
       "-XX:+ExitOnOutOfMemoryError -XX:+UseSerialGC")
RUNS = 3


def run(*command: str, timeout: int = 1800) -> str:
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command[:4])}: {result.stderr.strip()[-600:]}")
    return result.stdout


def build() -> None:
    run("docker", "build", "-q", "--target", "build", "-t", "freightflow/banking-build", str(ROOT / "banking-service"))
    run("docker", "build", "-q", "-t", "ffbase/banking:alpine", str(ROOT / "banking-service"))
    for name, dockerfile in BASES.items():
        if dockerfile:
            run("docker", "build", "-q", "-t", f"ffbase/banking:{name}", "-f", str(dockerfile), str(dockerfile.parent))


def scan(image: str) -> dict:
    out = run("docker", "run", "--rm", "-v", "/var/run/docker.sock:/var/run/docker.sock", TRIVY, "image", "--quiet",
              "--scanners", "vuln", "--list-all-pkgs", "--format", "json", image, timeout=3600)
    data = json.loads(out)
    severities, fixable, packages = {}, {}, {"os": 0, "java": 0}
    for result in data.get("Results", []):
        kind = "os" if result.get("Class") == "os-pkgs" else "java"
        packages[kind] += len(result.get("Packages", []) or [])
        for vulnerability in result.get("Vulnerabilities", []) or []:
            severity = vulnerability["Severity"]
            severities[severity] = severities.get(severity, 0) + 1
            if vulnerability.get("FixedVersion"):
                fixable[severity] = fixable.get(severity, 0) + 1
    os_info = data.get("Metadata", {}).get("OS", {})
    return {"os": f"{os_info.get('Family', '')} {os_info.get('Name', '')}".strip(), "packages": packages,
            "vulnerabilities": severities, "fixable": fixable}


def started(name: str) -> dict:
    override = Path(tempfile.mkstemp(suffix=".yml")[1])
    environment = "" if name == "alpine" else f"\n    environment:\n      JDK_JAVA_OPTIONS: \"{JVM}\""
    override.write_text(f"""services:
  banking-service:
    image: ffbase/banking:{name}
    build: !reset null
    healthcheck:
      disable: true{environment}
""")
    compose = ["docker", "compose", "--project-directory", str(ROOT), "-f", str(ROOT / "docker-compose.yml"),
               "-f", str(override)]
    seconds, memory, served = [], [], []
    for _ in range(RUNS):
        run(*compose, "up", "-d", "--no-deps", "--force-recreate", "banking-service")
        deadline = time.monotonic() + 300
        reported = None
        while time.monotonic() < deadline and reported is None:
            logs = run(*compose, "logs", "--no-log-prefix", "banking-service")
            match = re.search(r"Started BankingServiceApplication in ([0-9.]+) seconds", logs)
            reported = float(match.group(1)) if match else None
            time.sleep(2)
        seconds.append(reported)
        time.sleep(60)
        used = run("docker", "stats", "--no-stream", "--format", "{{.MemUsage}}", "freightflow-banking-service-1")
        memory.append(float(re.match(r"([0-9.]+)", used).group(1)))
        try:
            harness.TOKENS.forget("operator")
            harness.call("operator", "GET", "/api/v1/accounts?size=1")
            served.append(True)
        except Exception:  # noqa: BLE001 recorded
            served.append(False)
    override.unlink()
    valid = [s for s in seconds if s is not None]
    return {"startup_seconds": seconds, "startup_median": statistics.median(valid) if valid else None,
            "memory_mib_after_60s": memory, "served_through_gateway": served}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", required=True)
    args = parser.parse_args()
    build()
    results = {}
    for name in BASES:
        image = f"ffbase/banking:{name}"
        size = int(run("docker", "image", "inspect", "--format", "{{.Size}}", image).strip())
        results[name] = {"image_bytes": size, **scan(image), **started(name)}
        print(name, json.dumps(results[name]), flush=True)
    # Back to the stack's own banking.
    run("docker", "compose", "--project-directory", str(ROOT), "up", "-d", "--wait", "--no-deps",
        "--force-recreate", "banking-service")
    Path(args.json).write_text(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
