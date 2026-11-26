# 46. Point-in-time recovery

Date: 2026-11-27 · Status: Accepted · Builds on ADR 0001 (database per service), ADR 0039 (internal CA), ADR 0041
(roles)

## Context
Every service keeps its state in its own Postgres volume, and nothing copied it anywhere. A lost or corrupted volume
lost the ledger, and a bad migration or hand edit could not be undone. The Phase 7 brief asks for three things:
- point-in-time recovery for each database, with base backups and WAL archiving to a local object store;
- a measured restore drill;
- a runbook for what a restore to an earlier point means for the outbox, the inbox, Kafka offsets and idempotency
  keys. A restore must pay nothing twice.

## Sources, checked 2026-11-26
- pgBackRest 2.58 (Alpine 3.24's community package; upstream's latest is 2.60.1, released 2026-11-16): archive-push,
  backup, restore with `--type=time` and `--target-action=promote`, `--delta`, the S3 repository with path-style URIs
  and its own CA file, repository encryption (`aes-256-cbc`).
- PostgreSQL 17: `archive_mode`, `archive_command`, `archive_timeout`, recovery targets.
- MinIO: its GitHub repository is archived; the last release is RELEASE.2025-10-15, and the `minio/minio` Docker Hub
  repository no longer exists. It cannot be the local object store.
- SeaweedFS 4.55 (2026-11-23): `weed server` with its S3 gateway. The flags `-s3.cert.file`, `-s3.key.file`,
  `-s3.config` and `-s3.ip.bind`, and `-ip.bind` for the internal servers, read from `weed/command/server.go` at the
  4.55 tag.

## Decision
- **pgBackRest in each database container.** It is a Postgres image of our own, with pgBackRest added. Postgres
  archives every WAL segment as it fills, and at least every 60 s (`archive_timeout`): that is the bound on what a
  lost volume can cost.
- **Base backups:** a full one when the stanza has none, then daily, keeping two. They are taken by a loop beside
  Postgres, as the certificate watcher already runs.
- **The repository is a SeaweedFS S3 bucket:**
  - one stanza per database;
  - over TLS verified against the internal CA;
  - encrypted by pgBackRest with a passphrase from `secrets/`;
  - one S3 identity that may read, write and list.

  SeaweedFS's master, volume server and filer bind to its loopback; only the S3 gateway listens on the network. Its
  certificate comes from the agent, and the server restarts when the certificate is renewed. An archive-push in that
  second fails, and Postgres retries it.
- **The configuration is written at start into tmpfs, 0600,** credentials included: nothing secret is on the image
  or the volume.
- **A restore is one command** (`infra/postgres/restore.sh`), run as a one-off container on the stopped database's
  volume. pgBackRest writes the `restore_command`; the next start recovers to the target.
- **After a restore, `scripts/after_restore.py` puts the stack back in step before the service takes traffic:**
  - Each aggregate's event sequence is moved past the highest on its topics. Otherwise a consumer that orders by
    sequence would drop the new events as stale.
  - Every hold banking lost gets a REAUTHORISE, keyed per lost hold as logistics keys a reauthorisation after an
    expiry (ADR 0028). Running it twice makes no second hold.
  - Captures the restore lost are listed for an operator, never collected automatically.
  - A restored consumer's groups are reset to the restore point, so the events it lost are read again. The restored
    `processed_events` applies each once.
- **Nothing is paid twice, by construction.** A banking idempotency key lives in the banking database:
  - a request from before the restore point keeps its key and its stored answer;
  - one from after it has lost both, so its replay is its only run.

  A hold is captured once by constraint, and a lost hold is reauthorised once by key.
- **The drill measures it.** `scripts/restore_drill.py`, run by the Resilience workflow, restores banking twice:
  1. to a point in time, checking that the restored holds are exactly those at the target;
  2. to the latest WAL after a crash.

  It measures RTO and RPO for each, runs `after_restore.py` twice, and requires every invariant to hold and no
  shipment to hold twice. The numbers are in `docs/benchmarks/restore.md`.

## Consequences
- **RPO is bounded by `archive_timeout`,** 60 s of commits on a quiet database and less under load, when segments
  fill sooner. Synchronous archiving, or a streaming replica, would bound it further, at a cost in latency or in a
  second server.
- **The object store shares the host with the databases**, so a lost host loses both. Production puts the repository
  in another region with object lock, and the passphrase in a secrets manager (Phase 8).
- **A restore to an earlier point loses what happened after it.** The runbook names the parts:
  - holds are reauthorised;
  - captures go to an operator to collect;
  - the events stay on the topics, and consumers acted on them.

  This is the price of going back; the alternative to an undo is a forward correction, which is preferred whenever
  the damage is known (a REVERSAL, ADR 0004).
- **Each database container now also runs pgBackRest**, more memory under the same `mem_limit`, measured with
  everything else (ADR 0029).
- **The `objectstore` container is new**, with a memory limit and a line in ADR 0029 once its peak is measured.
