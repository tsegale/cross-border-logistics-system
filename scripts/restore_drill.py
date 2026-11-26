"""The restore drill (ADR 0046, docs/runbooks/restore.md): banking restored twice, each measured.

1. To a point in time. Bookings before and after the point T, then banking restored to T. RTO is the time from
   stopping banking until the gateway serves its accounts again. The restored database must hold exactly the holds
   that existed at T.
2. To the latest archived WAL, after its database crashed (SIGKILL), as if its volume were lost. RPO is what the
   archive did not have yet: the holds and the seconds of commits lost.

After each, scripts/after_restore.py puts the stack back in step: sequences moved past the topics, a REAUTHORISE
for every hold the restore lost. Then nothing may be paid twice: every shipment has at most one active hold, the
ledger reconciles, and the invariant auditor passes. after_restore.py run a second time must change nothing.

    python scripts/restore_drill.py --json restore.json

Needs the default stack (with its backups) and Docker.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
from datetime import datetime, timezone
from pathlib import Path

REPOSITORY = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(REPOSITORY / "benchmarks"))
sys.path.insert(0, str(REPOSITORY / "scripts"))
import events_to_email as harness  # noqa: E402

COMPOSE = ["docker", "compose", "--project-directory", str(REPOSITORY)]
BATCH = 10


def log(message: str) -> None:
    print(f"{datetime.now(timezone.utc):%H:%M:%S} {message}", flush=True)


def compose(*args: str) -> str:
    result = subprocess.run([*COMPOSE, *args], capture_output=True, text=True, timeout=1800)
    if result.returncode != 0:
        raise RuntimeError(f"compose {' '.join(args)}: {result.stderr.strip()[-800:]}")
    return result.stdout


def sql(database: str, statement: str) -> str:
    return compose("exec", "-T", f"{database}-db", "sh", "-c",
                   f'psql -U "$POSTGRES_USER" -d {database} -v ON_ERROR_STOP=1 -qtAXc "{statement}"').strip()


def pgbackrest(*args: str) -> str:
    return compose("exec", "-T", "banking-db", "pgbackrest", "--config=/tmp/pgbackrest/pgbackrest.conf",
                   "--stanza=banking", *args)


def book(account: str, run: str, offset: int) -> list[str]:
    trackings = [harness.book(account, f"restore-{run}", offset + i)[0] for i in range(BATCH)]
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        statuses = [harness.call("customer-hanseatic-imports", "GET", f"/api/v1/shipments/track/{t}")["status"]
                    for t in trackings]
        if all(status == "BOOKED" for status in statuses):
            return trackings
        time.sleep(3)
    raise RuntimeError(f"bookings from {offset} were not all booked: {statuses}")


def serving_again(started: float) -> float:
    """Seconds from `started` until banking answers through the gateway."""
    deadline = time.monotonic() + 900
    while time.monotonic() < deadline:
        try:
            harness.call("operator", "GET", "/api/v1/accounts?size=1")
            return round(time.monotonic() - started, 1)
        except Exception:  # noqa: BLE001 not yet
            harness.TOKENS.forget("operator")
            time.sleep(2)
    raise RuntimeError("banking did not serve again within 15 minutes")


def after_restore(name: str) -> dict:
    out = REPOSITORY / f"after-restore-{name}.json"
    subprocess.run([sys.executable, str(REPOSITORY / "scripts" / "after_restore.py"), "banking", "--json", str(out)],
                   check=True, timeout=900)
    result = json.loads(out.read_text())
    out.unlink()
    return result


def settled_once() -> dict:
    """No shipment holds twice; every reauthorisation done; the ledger reconciles; every invariant holds."""
    deadline = time.monotonic() + 300
    while time.monotonic() < deadline:
        pending = sql("logistics", "SELECT count(*) FROM shipment_payments WHERE command = 'REAUTHORISE' "
                                   "AND status = 'PENDING'")
        if pending == "0":
            break
        time.sleep(5)
    twice = sql("banking", "SELECT count(*) FROM (SELECT reference FROM holds WHERE status = 'ACTIVE' "
                           "GROUP BY reference HAVING count(*) > 1) x")
    out = REPOSITORY / "restore-audit.json"
    audit = subprocess.run([sys.executable, str(REPOSITORY / "scripts" / "audit_invariants.py"), "--json", str(out)],
                           capture_output=True, text=True, timeout=1800)
    print(audit.stdout[-2500:], flush=True)
    verdict = json.loads(out.read_text()) if out.exists() else {"holds": False}
    out.unlink(missing_ok=True)
    return {"reauthorisations_pending": int(pending), "shipments_holding_twice": int(twice),
            "invariants_hold": verdict.get("holds"), "audit": verdict}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--json", required=True)
    args = parser.parse_args()
    run = str(int(time.time()))
    _, account = harness.setup(run)
    result: dict = {"batch": BATCH}

    log("bookings before the backup")
    book(account, run, 0)
    pgbackrest("backup", "--type=full")
    log("full backup taken; bookings before the point in time")
    book(account, run, 100)
    target = sql("banking", "SELECT to_char(now() AT TIME ZONE 'UTC', 'YYYY-MM-DD HH24:MI:SS.US') || '+00'")
    holds_at_target = int(sql("banking", "SELECT count(*) FROM holds"))
    # A commit after T, so that recovery has a record past the target to stop at.
    time.sleep(2)
    log(f"T = {target}, {holds_at_target} holds; bookings after it")
    after = book(account, run, 200)
    pgbackrest("check")  # switches and archives the current WAL segment

    log("restore 1: banking to T")
    started = time.monotonic()
    compose("stop", "banking-service", "banking-db")
    compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "banking-db",
            "/etc/freightflow/postgres/restore.sh", "--type=time", f"--target={target}", "--target-action=promote")
    compose("up", "-d", "--wait", "--no-deps", "banking-db", "banking-service")
    rto = serving_again(started)
    holds_after = int(sql("banking", "SELECT count(*) FROM holds"))
    first = after_restore("pitr")
    again = after_restore("pitr-again")
    result["point_in_time"] = {
        "target": target, "rto_seconds": rto, "holds_at_target": holds_at_target, "holds_after_restore": holds_after,
        "bookings_after_target": len(after), "after_restore": first, "after_restore_again": again,
        **settled_once()}
    log(f"restore 1: RTO {rto} s; holds {holds_after} of {holds_at_target} at T; "
        f"{first.get('reauthorised')} reauthorised, then {again.get('reauthorised')} on a second run")

    log("restore 2: banking crashes; restored to the latest archived WAL")
    book(account, run, 300)
    before = sql("banking", "SELECT count(*) || ' ' || extract(epoch FROM max(created_at)) FROM holds").split()
    compose("kill", "-s", "SIGKILL", "banking-db")
    started = time.monotonic()
    compose("stop", "banking-service")
    compose("run", "--rm", "--no-deps", "--entrypoint", "/bin/sh", "banking-db",
            "/etc/freightflow/postgres/restore.sh")
    compose("up", "-d", "--wait", "--no-deps", "banking-db", "banking-service")
    rto = serving_again(started)
    after_latest = sql("banking", "SELECT count(*) || ' ' || extract(epoch FROM max(created_at)) FROM holds").split()
    lost = after_restore("latest")
    result["latest"] = {
        "rto_seconds": rto, "holds_before_crash": int(before[0]), "holds_after_restore": int(after_latest[0]),
        "rpo_holds_lost": int(before[0]) - int(after_latest[0]),
        "rpo_seconds": round(float(before[1]) - float(after_latest[1]), 1), "after_restore": lost,
        **settled_once()}
    log(f"restore 2: RTO {rto} s; RPO {result['latest']['rpo_holds_lost']} holds, "
        f"{result['latest']['rpo_seconds']} s of commits")

    Path(args.json).write_text(json.dumps(result, indent=2))
    ok = all(result[part]["invariants_hold"] and result[part]["shipments_holding_twice"] == 0
             and result[part]["reauthorisations_pending"] == 0 for part in ("point_in_time", "latest"))
    ok = ok and result["point_in_time"]["holds_after_restore"] == holds_at_target
    ok = ok and result["point_in_time"]["after_restore_again"].get("reauthorised") == 0
    log("drill passed" if ok else "drill FAILED")
    sys.exit(0 if ok else 1)


if __name__ == "__main__":
    main()
