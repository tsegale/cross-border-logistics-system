# Restore a database

When: a database's volume is lost or corrupt, or a bad change (a migration, an operator's hand edit) has to be
undone by going back to a point in time. ADR 0046.

**What exists.** Every database (banking, logistics, notification, keycloak) archives each WAL segment to the
object store as it fills, and at least every 60 s (`ARCHIVE_TIMEOUT_SECONDS`). It takes a full base backup at
first start and then daily (`BACKUP_INTERVAL_SECONDS`), keeping two (`BACKUP_RETENTION_FULL`). Everything is
encrypted with `secrets/backup_cipher_pass`, which is lost with the secrets if nobody copied it.

**Check first.**
- What backups and archive exist:
  `docker compose exec banking-db pgbackrest --config=/tmp/pgbackrest/pgbackrest.conf --stanza=banking info`.
- Whether archiving works now: the same command with `check`. It switches the WAL segment and waits for it in the
  archive.
- The point to restore to. For a bad change, a moment before it, in UTC: the journal entry's `created_at`, a
  migration's time in `flyway_schema_history`, the trace of the request.

## 1. Restore

Stop the service first, so nothing writes while the database is down:

```
docker compose stop banking-service banking-db
# To a point in time:
docker compose run --rm --no-deps --entrypoint /bin/sh banking-db /etc/freightflow/postgres/restore.sh \
  --type=time "--target=2026-11-27 10:15:00+00" --target-action=promote
# Or to the latest archived WAL, after losing the volume:
docker compose run --rm --no-deps --entrypoint /bin/sh banking-db /etc/freightflow/postgres/restore.sh
docker compose up -d --wait banking-db
```

The database replays the archive up to the target and opens. Leave the service stopped until step 2 is done.

## 2. Put the rest of the stack in step

The restored database has forgotten everything after the restore point, and the rest of the stack has not:

| What | After a restore to an earlier point | What to do |
| --- | --- | --- |
| Its outbox | Events written after the point are gone. They were published, and consumers acted on them | Nothing to republish. The table below says what consumers did with them |
| Its event sequences | They restart from the point, so new events reuse numbers the topics already hold. Notification's shipment projection drops an event whose sequence it has seen, as stale | `after_restore.py` moves each aggregate's sequence past the highest on its topics |
| Its inbox (`processed_events`), if it consumes (logistics, notification) | Restored with it, while Kafka kept its offsets past the point: events it processed after the point are never read again, and their effects stay lost | `after_restore.py <service> --restored-to <point>` resets its consumer groups to the point; the restored `processed_events` applies each event once |
| Kafka consumer offsets | In Kafka, not in the database: not restored | Reset only for the restored consumer, as above. Every other consumer keeps its offsets |
| Idempotency keys (banking) | Keys stored after the point are gone. A retry of such a request runs again, against a ledger that no longer has its first run | This is what keeps a replay from paying twice: the first run is gone with its key, so the second run is the only one |
| Holds made after the point (banking) | Gone, while logistics still names them on its shipments | `after_restore.py` enqueues a REAUTHORISE for each: one per lost hold, by the key logistics gives a reauthorisation, so a second run makes no second hold |
| Captures after the point (banking) | Gone: the customer was not charged, though logistics says CAPTURED | `after_restore.py` lists these shipments. Collecting them is an operator's decision, with the customer's agreement, through a new hold and capture |

```
python scripts/after_restore.py banking
# A consumer, restored to a point: its groups go back to that point too
python scripts/after_restore.py logistics --restored-to 2026-11-27T10:15:00Z
docker compose up -d --wait banking-service
python scripts/audit_invariants.py
```

## 3. Check

The audit must pass: entries balanced, captures equal across both services, every topic in order. A shipment that
holds twice is a failed restore: stop and escalate.

**Measured.** The drill (`scripts/restore_drill.py`, the Resilience workflow) restores banking to a point in time
and, after a crash, to the latest WAL. It measures RTO and RPO each time and checks that nothing is held or paid
twice: see `docs/benchmarks/restore.md`.
