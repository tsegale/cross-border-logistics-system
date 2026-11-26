"""The invariant auditor across the whole stack (ADR 0044): run after every chaos experiment and restore drill.

1. Each service's own audit, as an auditor through the gateway: banking's ledger invariants and logistics' payment
   and outbox invariants (GET /api/v1/ledger/invariants, /api/v1/logistics/invariants).
2. Every shipment paid once, across both services: the captures logistics recorded as confirmed must equal, per
   currency, in count and in amount, the holds banking captured. Captures still in flight settle within seconds, so a
   difference is checked again a few times before it counts.
3. Every event published once and in order: each topic is read from the beginning, inside the broker's container as
   its admin. Per aggregate, the first copy of each sequence must come in order without a gap. A second copy of an
   eventId is a redelivery, which consumers drop (processed_events, ADR 0026), and is reported. Two different
   eventIds with one sequence break the rule.

    python scripts/audit_invariants.py              # exit 1 if anything is broken
    python scripts/audit_invariants.py --json out.json

Needs the default stack and Docker; nothing on the host beyond Python.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import time
import urllib.request
from decimal import Decimal
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from internal_ca import ssl_context  # noqa: E402
from keycloak_token import PEOPLE, read_env_file, user_token  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent
GATEWAY = os.environ.get("GATEWAY_URL", "https://127.0.0.1:8080")
COMPOSE = ["docker", "compose", "--project-directory", str(REPOSITORY)]
AUDITS = {"banking": "/api/v1/ledger/invariants", "logistics": "/api/v1/logistics/invariants"}
TOPIC = re.compile(r"^freightflow\.[a-z-]+\.v[0-9]+$")
ADMIN_CLIENT = "/tmp/kafka-tls/admin.properties"
# A tools JVM inside the broker's memory limit (ADR 0029): a small heap, no JIT tiers it does not need.
TOOL_ENV = 'KAFKA_HEAP_OPTS=-Xmx64m KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1"'


def audit(path: str, token: str) -> dict:
    request = urllib.request.Request(GATEWAY + path, headers={"Authorization": f"Bearer {token}"})
    with urllib.request.urlopen(request, timeout=120, context=ssl_context()) as response:
        return json.load(response)


def captures(report: dict) -> dict[str, tuple[int, Decimal]]:
    return {currency: (total["count"], Decimal(str(total["amount"])))
            for currency, total in (report.get("captures") or {}).items()}


def kafka(command: str) -> str:
    result = subprocess.run([*COMPOSE, "exec", "-T", "kafka", "bash", "-c", f"{TOOL_ENV} {command}"],
                            capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise SystemExit(f"kafka: {command.split()[0]} failed: {result.stderr.strip()[-400:]}")
    return result.stdout


def topics_in_order() -> tuple[dict[str, list[str]], dict[str, int]]:
    """Per topic, what breaks the order rule, and how many redeliveries there were."""
    names = [name for name in kafka(f"/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 "
                                    f"--command-config {ADMIN_CLIENT} --list").split() if TOPIC.match(name)]
    broken: dict[str, list[str]] = {}
    redelivered: dict[str, int] = {}
    for topic in sorted(names):
        lines = kafka(f"/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 "
                      f"--consumer.config {ADMIN_CLIENT} --topic {topic} --from-beginning --timeout-ms 15000 "
                      f"2> /dev/null || true").splitlines()
        seen: dict[str, dict[int, str]] = {}
        last: dict[str, int] = {}
        problems: list[str] = []
        copies = 0
        for line in lines:
            try:
                envelope = json.loads(line)
            except json.JSONDecodeError:
                continue
            aggregate, sequence, event = envelope["aggregateId"], int(envelope["sequence"]), envelope["eventId"]
            known = seen.setdefault(aggregate, {})
            if sequence in known:
                if known[sequence] == event:
                    copies += 1
                else:
                    problems.append(f"{aggregate} #{sequence} is two events: {known[sequence]} and {event}")
                continue
            # The topic may begin after the aggregate did (retention); from its first event on, no gap.
            if aggregate in last and sequence != last[aggregate] + 1:
                problems.append(f"{aggregate} #{sequence} after #{last[aggregate]}")
            known[sequence] = event
            last[aggregate] = max(sequence, last.get(aggregate, 0))
        print(f"  {topic:34} {len(lines):6} records, {len(seen):5} aggregates, {copies} redelivered, "
              f"{len(problems)} broken", flush=True)
        if problems:
            broken[topic] = problems
        if copies:
            redelivered[topic] = copies
    return broken, redelivered


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", help="write the verdict here")
    parser.add_argument("--settle", type=int, default=6, help="checks of the capture totals before they count")
    args = parser.parse_args()
    token = user_token("auditor", read_env_file()[PEOPLE["auditor"]])
    verdict: dict = {"services": {}, "captures": {}, "topics": {}}
    failed = False

    print("services:")
    reports = {}
    for service, path in AUDITS.items():
        reports[service] = report = audit(path, token)
        verdict["services"][service] = {i["name"]: i["violations"] for i in report["invariants"]}
        for invariant in report["invariants"]:
            state = "holds" if invariant["violations"] == 0 else f"BROKEN by {invariant['violations']} row(s)"
            print(f"  {service:10} {invariant['name']:26} {state}")
            for example in invariant["examples"]:
                print(f"             {example}")
        failed |= not report["holds"]

    print("captures, logistics against banking:")
    for attempt in range(args.settle):
        banking, logistics = captures(reports["banking"]), captures(reports["logistics"])
        if banking == logistics:
            break
        time.sleep(10)
        reports = {service: audit(path, token) for service, path in AUDITS.items()}
    for currency in sorted(set(banking) | set(logistics)):
        b, l = banking.get(currency, (0, Decimal(0))), logistics.get(currency, (0, Decimal(0)))
        same = b[0] == l[0] and b[1] == l[1]
        print(f"  {currency}  banking {b[0]:5} for {b[1]:>14}   logistics {l[0]:5} for {l[1]:>14}   "
              f"{'equal' if same else 'DIFFERENT'}")
        verdict["captures"][currency] = {"banking": [b[0], str(b[1])], "logistics": [l[0], str(l[1])], "equal": same}
        failed |= not same

    print("topics, per aggregate:")
    broken, redelivered = topics_in_order()
    verdict["topics"] = {"broken": broken, "redelivered": redelivered}
    for topic, problems in broken.items():
        for problem in problems[:5]:
            print(f"  {topic}: {problem}")
    failed |= bool(broken)

    verdict["holds"] = not failed
    print("verdict:", "every invariant holds" if not failed else "BROKEN")
    if args.json:
        Path(args.json).write_text(json.dumps(verdict, indent=2))
    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
