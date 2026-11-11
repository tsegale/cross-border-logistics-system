#!/usr/bin/env python3
"""System account balance read at a million postings, before and after a checkpoint (ADR 0010).

    python benchmarks/system_balance_read.py [--postings 1000000] [--after 1000] [--runs 30]

Starts a throwaway PostgreSQL 17 container, applies banking-service's Flyway migrations in order, and writes
--postings unlocked postings against CLEARING-EUR (each balanced by one against FX_POSITION-EUR) in transactions of
100,000. Then it times, with EXPLAIN ANALYZE on the server:

  without checkpoint   the read SystemAccountBalances makes when no checkpoint exists: a sum over every posting
  roll-up              sql/system-balance-checkpoint.sql, the statement SystemBalanceCheckpoints runs
  with checkpoint      the read once a checkpoint exists, after --after more postings in later transactions

The roll-up statement is read from banking-service, so this measures exactly what the service runs. Data is
generated with triggers off (session_replication_role = replica): the journal's balance trigger would only slow the
setup, and the reads do not depend on it. Standard library only; needs Docker.
"""

from __future__ import annotations

import argparse
import json
import platform
import statistics
import subprocess
import sys
import time
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MIGRATIONS = ROOT / "banking-service" / "src" / "main" / "resources" / "db" / "migration"
ROLL_UP = ROOT / "banking-service" / "src" / "main" / "resources" / "sql" / "system-balance-checkpoint.sql"
OUT = ROOT / "benchmarks" / "out"
CLEARING_EUR = "01930000-0000-7000-8000-000000000007"
FX_POSITION_EUR = "01930000-0000-7000-8000-000000000009"
IMAGE = "postgres:17-alpine"
BATCH = 100_000

NO_CHECKPOINT_READ = f"""
SELECT a.balance
       + COALESCE((SELECT SUM(s.balance) FROM account_balance_shards s WHERE s.account_id = a.id), 0)
       + COALESCE((SELECT SUM(p.amount) FROM postings p
                   WHERE p.account_id = a.id AND p.running_balance IS NULL), 0)
FROM accounts a WHERE a.id = '{CLEARING_EUR}'"""

LATEST_CHECKPOINT = f"""
SELECT balance, covered_below FROM system_balance_checkpoints
WHERE account_id = '{CLEARING_EUR}' ORDER BY covered_below DESC LIMIT 1"""


def checkpoint_read(balance: str, covered_below: str) -> str:
    return f"""
SELECT a.balance
       + COALESCE((SELECT SUM(s.balance) FROM account_balance_shards s WHERE s.account_id = a.id), 0)
       + {balance}
       + COALESCE((SELECT SUM(p.amount) FROM postings p
                   WHERE p.account_id = a.id AND p.running_balance IS NULL AND p.txid >= '{covered_below}'::xid8), 0)
FROM accounts a WHERE a.id = '{CLEARING_EUR}'"""


class Postgres:
    def __init__(self) -> None:
        self.name = f"ff-balance-bench-{uuid.uuid4().hex[:8]}"

    def start(self) -> None:
        subprocess.run(["docker", "run", "-d", "--rm", "--name", self.name, "-e", "POSTGRES_PASSWORD=bench",
                        "-e", "POSTGRES_DB=banking", IMAGE], check=True, capture_output=True)
        deadline = time.time() + 120
        while time.time() < deadline:
            ready = subprocess.run(["docker", "exec", self.name, "pg_isready", "-U", "postgres", "-d", "banking"],
                                   capture_output=True)
            if ready.returncode == 0:
                try:
                    # The image's init scripts run a temporary server before the database exists: keep waiting.
                    if self.sql("SELECT 1").strip() == "1":
                        return
                except RuntimeError:
                    pass
            time.sleep(1)
        raise RuntimeError("PostgreSQL did not start within 120 s")

    def stop(self) -> None:
        subprocess.run(["docker", "rm", "-f", self.name], capture_output=True)

    def sql(self, statement: str) -> str:
        done = subprocess.run(["docker", "exec", "-i", self.name, "psql", "-U", "postgres", "-d", "banking",
                               "-v", "ON_ERROR_STOP=1", "-qtA"], input=statement, text=True, capture_output=True)
        if done.returncode != 0:
            raise RuntimeError(f"psql failed: {done.stderr.strip()}")
        return done.stdout

    def execution_ms(self, statement: str) -> float:
        plan = json.loads(self.sql(f"EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) {statement}"))
        return float(plan[0]["Execution Time"])

    def timed(self, statement: str, runs: int) -> list[float]:
        self.execution_ms(statement)  # warm the cache once; every reported run is a warm read
        return [self.execution_ms(statement) for _ in range(runs)]


def migrate(db: Postgres) -> None:
    def version(path: Path) -> int:
        return int(path.name.split("__")[0][1:])
    for path in sorted(MIGRATIONS.glob("V*__*.sql"), key=version):
        db.sql(path.read_text(encoding="utf-8"))


def write_postings(db: Postgres, count: int) -> None:
    """count journal entries of 1.0000 EUR: CLEARING-EUR +1, FX_POSITION-EUR -1, unlocked (no running balance)."""
    written = 0
    while written < count:
        n = min(BATCH, count - written)
        db.sql(f"""
SET session_replication_role = replica;
BEGIN;
CREATE TEMP TABLE batch ON COMMIT DROP AS SELECT gen_random_uuid() AS entry FROM generate_series(1, {n});
INSERT INTO journal_entries (id, entry_type, reference, created_at)
    SELECT entry, 'DEPOSIT', 'BENCH', now() FROM batch;
INSERT INTO postings (id, journal_entry_id, account_id, amount, currency, running_balance, created_at)
    SELECT gen_random_uuid(), entry, '{CLEARING_EUR}'::uuid, 1.0000, 'EUR', NULL::numeric, now() FROM batch
    UNION ALL
    SELECT gen_random_uuid(), entry, '{FX_POSITION_EUR}'::uuid, -1.0000, 'EUR', NULL::numeric, now() FROM batch;
COMMIT;""")
        written += n


def summary(samples: list[float]) -> dict[str, float]:
    ordered = sorted(samples)
    return {"median_ms": statistics.median(ordered), "p95_ms": ordered[max(0, int(len(ordered) * 0.95) - 1)],
            "min_ms": ordered[0], "max_ms": ordered[-1]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--postings", type=int, default=1_000_000)
    parser.add_argument("--after", type=int, default=1_000, help="postings written after the checkpoint")
    parser.add_argument("--runs", type=int, default=30)
    args = parser.parse_args()

    db = Postgres()
    db.start()
    try:
        migrate(db)
        started = time.time()
        write_postings(db, args.postings)
        db.sql("VACUUM (ANALYZE) postings;")
        print(f"wrote {args.postings:,} postings per account in {time.time() - started:.0f} s", file=sys.stderr)

        without = db.timed(NO_CHECKPOINT_READ, args.runs)
        expected = db.sql(NO_CHECKPOINT_READ).strip()

        roll_up = ROLL_UP.read_text(encoding="utf-8")
        roll_up = "\n".join(line for line in roll_up.splitlines() if not line.startswith("--"))
        roll_up = (roll_up.replace(":accountId", f"'{CLEARING_EUR}'::uuid")
                   .replace(":id", f"'{uuid.uuid4()}'::uuid").replace(":createdAt", "now()"))
        roll_up_ms = db.execution_ms(roll_up)

        for _ in range(10):
            write_postings(db, args.after // 10)
        db.sql("VACUUM (ANALYZE) postings;")
        balance, covered_below = db.sql(LATEST_CHECKPOINT).strip().split("|")
        with_checkpoint = db.timed(checkpoint_read(balance, covered_below), args.runs)
        lookup = db.timed(LATEST_CHECKPOINT, args.runs)
        after_checkpoint = db.sql(checkpoint_read(balance, covered_below)).strip()
        after_full = db.sql(NO_CHECKPOINT_READ).strip()
        if after_checkpoint != after_full:
            raise RuntimeError(f"checkpoint read {after_checkpoint} differs from the full sum {after_full}")

        result = {
            "postings_per_account": args.postings, "postings_after_checkpoint": args.after, "runs": args.runs,
            "machine": f"{platform.processor() or platform.machine()} ({platform.system()} {platform.release()})",
            "postgres": db.sql("SHOW server_version").strip(),
            "balance_before_checkpoint": expected, "balance_after": after_full,
            "without_checkpoint": summary(without), "roll_up_ms": roll_up_ms,
            "checkpoint_lookup": summary(lookup), "with_checkpoint": summary(with_checkpoint),
        }
        OUT.mkdir(parents=True, exist_ok=True)
        (OUT / "system-balance-read.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
        print("| Read | Median ms | p95 ms | Min ms | Max ms |")
        print("| --- | ---: | ---: | ---: | ---: |")
        for label, key in (("without checkpoint (sum over every posting)", "without_checkpoint"),
                           ("checkpoint lookup", "checkpoint_lookup"),
                           (f"with checkpoint (+{args.after:,} postings after it)", "with_checkpoint")):
            s = result[key]
            print(f"| {label} | {s['median_ms']:,.3f} | {s['p95_ms']:,.3f} | {s['min_ms']:,.3f} | {s['max_ms']:,.3f} |")
        print(f"\nroll-up of {args.postings:,} postings: {roll_up_ms:,.1f} ms; balances agree: {after_full}")
    finally:
        db.stop()


if __name__ == "__main__":
    main()
