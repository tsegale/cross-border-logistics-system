"""After a database is restored to an earlier point (ADR 0046, docs/runbooks/restore.md): put the rest of the
stack back in step with it, before the services take traffic again. An operator's step, run as each database's
owner; the services never read each other's databases.

1. Event sequences. The restored service hands out sequence numbers again from the restore point, but its topics
   still hold the events it published after it. A consumer that orders by sequence (notification's shipment
   projection) would drop the new events as stale. Each aggregate's sequence is moved past the highest on its
   topics.
2. Lost holds (banking only). Holds banking made after the restore point are gone, while logistics still names
   them on its shipments. Each one gets a REAUTHORISE, the command logistics already sends for an expired hold
   (ADR 0028), with that command's key: one per lost hold, so running this twice makes no second hold. Money that
   a lost capture should have taken stays the operator's to collect: the script lists those shipments.
3. Consumer offsets (logistics, notification). Kafka kept the restored consumer's offsets past the restore point,
   so the events it processed after it would never be read again, and their effects stay lost. Its groups are reset
   to the restore point; processed_events, restored with it, applies each of them once. The service must be stopped.

    python scripts/after_restore.py banking
    python scripts/after_restore.py logistics --restored-to 2026-11-27T10:15:00Z

Needs the stack and Docker; reads the topics inside the broker's container, as audit_invariants.py does.
"""
from __future__ import annotations

import argparse
import json
import subprocess
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import audit_invariants  # noqa: E402

REPOSITORY = Path(__file__).resolve().parent.parent
COMPOSE = ["docker", "compose", "--project-directory", str(REPOSITORY)]
TOPICS = {
    "notification": [],
    "banking": ["freightflow.account.v1", "freightflow.hold.v1", "freightflow.journal-entry.v1",
                "freightflow.approval.v1"],
    "logistics": ["freightflow.shipment.v1"],
}


def sql(database: str, statement: str) -> str:
    result = subprocess.run([*COMPOSE, "exec", "-T", f"{database}-db", "sh", "-c",
                             f'psql -U "$POSTGRES_USER" -d {database} -v ON_ERROR_STOP=1 -qtAX'],
                            input=statement, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise SystemExit(f"{database}: {result.stderr.strip()[-500:]}")
    return result.stdout.strip()


def highest_sequences(topics: list[str]) -> dict[str, int]:
    highest: dict[str, int] = {}
    for topic in topics:
        lines = audit_invariants.kafka(
            f"/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:29092 "
            f"--consumer.config {audit_invariants.ADMIN_CLIENT} --topic {topic} --from-beginning "
            f"--timeout-ms 15000 2> /dev/null || true").splitlines()
        for line in lines:
            try:
                envelope = json.loads(line)
            except json.JSONDecodeError:
                continue
            aggregate = envelope["aggregateId"]
            highest[aggregate] = max(highest.get(aggregate, 0), int(envelope["sequence"]))
    return highest


def realign_sequences(service: str) -> int:
    highest = highest_sequences(TOPICS[service])
    if not highest:
        return 0
    values = ",".join(f"('{aggregate}'::uuid, {sequence})" for aggregate, sequence in highest.items())
    moved = sql(service, f"""
        WITH topic(aggregate_id, highest) AS (VALUES {values}),
        moved AS (
            INSERT INTO event_sequences (aggregate_id, last_sequence)
            SELECT aggregate_id, highest FROM topic
            ON CONFLICT (aggregate_id) DO UPDATE SET last_sequence = EXCLUDED.last_sequence
            WHERE event_sequences.last_sequence < EXCLUDED.last_sequence
            RETURNING 1)
        SELECT count(*) FROM moved;""")
    return int(moved or 0)


def reauthorise_lost_holds() -> tuple[int, list[str]]:
    holds = set(sql("banking", "SELECT id FROM holds;").split())
    rows = sql("logistics", """
        SELECT s.id || ' ' || s.hold_id || ' ' || s.tracking_number || ' ' || s.payment_status FROM shipments s
        WHERE s.hold_id IS NOT NULL AND s.payment_status IN ('AUTHORISED', 'CAPTURE_PENDING', 'CAPTURED');""")
    lost, uncollected = [], []
    for row in rows.splitlines():
        shipment, hold, tracking, status = row.split()
        if hold in holds:
            continue
        (uncollected if status == "CAPTURED" else lost).append((shipment, hold, tracking))
    for shipment, hold, _ in lost:
        # The shipment's own AUTHORISE row, turned into the REAUTHORISE of the lost hold, with the key logistics
        # gives a reauthorisation (PaymentCommands.reauthorisationKey).
        sql("logistics", f"""
            CREATE TEMP TABLE planned AS SELECT * FROM shipment_payments
                WHERE shipment_id = '{shipment}' AND command = 'AUTHORISE' AND hold_kind = 'FREIGHT';
            UPDATE planned SET id = gen_random_uuid(), command = 'REAUTHORISE', status = 'PENDING', attempts = 0,
                idempotency_key = 'shipment-{shipment}-reauthorise-{hold}', hold_id = '{hold}',
                next_attempt_at = now(), completed_at = NULL, last_error = NULL, created_at = now(),
                updated_at = now();
            INSERT INTO shipment_payments SELECT * FROM planned ON CONFLICT DO NOTHING;
            DROP TABLE planned;""")
    return len(lost), [tracking for _, _, tracking in uncollected]


def reset_offsets(service: str, restored_to: str) -> list[str]:
    groups = [group for group in audit_invariants.kafka(
        f"/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 "
        f"--command-config {audit_invariants.ADMIN_CLIENT} --list").split() if group.startswith(f"{service}-service")]
    for group in groups:
        audit_invariants.kafka(
            f"/opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka:29092 "
            f"--command-config {audit_invariants.ADMIN_CLIENT} --group {group} --reset-offsets --all-topics "
            f"--to-datetime {restored_to.replace('Z', '').split('.')[0]}.000 --execute")
    return groups


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("service", choices=sorted(TOPICS))
    parser.add_argument("--restored-to", help="logistics, notification: the restore point, UTC, e.g. "
                                              "2026-11-27T10:15:00Z; resets the service's consumer groups to it")
    parser.add_argument("--json", help="write what was done here")
    args = parser.parse_args()
    done = {"service": args.service, "sequences_moved": realign_sequences(args.service)}
    print(f"{args.service}: {done['sequences_moved']} aggregates' sequences moved past their topics")
    if args.service == "banking":
        reauthorised, uncollected = reauthorise_lost_holds()
        done.update(reauthorised=reauthorised, captures_to_collect=uncollected)
        print(f"banking: {reauthorised} lost holds reauthorised; {len(uncollected)} captured shipments whose capture "
              f"was lost, to collect by hand: {', '.join(uncollected) or 'none'}")
    if args.service != "banking" and args.restored_to:
        done["offsets_reset"] = reset_offsets(args.service, args.restored_to)
        print(f"{args.service}: consumer groups reset to {args.restored_to}: {', '.join(done['offsets_reset'])}")
    if args.json:
        Path(args.json).write_text(json.dumps(done, indent=2))


if __name__ == "__main__":
    main()
