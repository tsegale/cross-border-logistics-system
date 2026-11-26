#!/bin/sh
# Restores this database from the object store (ADR 0046, docs/runbooks/restore.md). Runs as a one-off container on
# the stopped database's volume, with the service's own configuration:
#
#   docker compose stop banking-service banking-db
#   docker compose run --rm --no-deps --entrypoint /bin/sh banking-db /etc/freightflow/postgres/restore.sh \
#     --type=time "--target=2026-11-27 10:15:00+00" --target-action=promote
#   docker compose up -d --wait banking-db banking-service
#
# Without --type it restores to the last archived WAL segment. --delta reuses the files that are already right, so
# a restore onto an existing volume copies only what changed. pgBackRest writes the restore_command into the data
# directory; on the next start, start.sh writes the configuration it names again, and recovery runs to the target.
set -eu
. /etc/freightflow/postgres/backup.sh
[ -n "$STANZA" ] || { echo "restore: PGBACKREST_STANZA is not set"; exit 1; }
backup_config /tls/ca.pem
backup restore --delta "$@"
echo "restore: $STANZA restored ($*); start the database to recover to the target"
