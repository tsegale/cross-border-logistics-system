#!/bin/sh
# Postgres with TLS from the internal CA (ADR 0039), for every database container.
#
# The agent's key is 0640 in the postgres group, which Postgres refuses ("private key file has group or world
# access"): this copies it, with the certificate and the root, to 0600 files the server owns, then starts Postgres
# with TLS required (pg_hba.conf: hostssl only). When the agent renews the certificate, the copies are refreshed and
# the configuration reloaded: Postgres re-reads its certificate and key on a reload, and connections already open
# keep the old one.
#
# With PGBACKREST_STANZA set, it also archives every WAL segment to the object store and takes base backups
# (ADR 0046): a full one at start when the stanza has none, then one every BACKUP_INTERVAL_SECONDS.
# archive_timeout bounds the data a lost volume can cost to that many seconds of commits (the RPO).
set -eu
. /etc/freightflow/postgres/backup.sh

SOURCE="${TLS_SOURCE:-/tls}"
TARGET="${TLS_TARGET:-/var/lib/postgresql/tls}"
CHECK="${TLS_CHECK_SECONDS:-60}"

copy_certificates() {
  mkdir -p "$TARGET"
  for file in cert.pem key.pem ca.pem; do
    cp "$SOURCE/$file" "$TARGET/$file.new"
    chmod 0600 "$TARGET/$file.new"
    mv -f "$TARGET/$file.new" "$TARGET/$file"
  done
}

watch_certificates() {
  while sleep "$CHECK"; do
    if ! cmp -s "$SOURCE/cert.pem" "$TARGET/cert.pem"; then
      copy_certificates
      if pg_ctl reload -D "$PGDATA" > /dev/null; then
        echo "postgres tls: certificate renewed, configuration reloaded"
      fi
    fi
  done
}

backup_loop() {
  until pg_isready -q -h /var/run/postgresql; do sleep 2; done
  # A second start finds the stanza; an archive that does not match this cluster fails here, loudly.
  backup stanza-create > /dev/null || echo "backup: stanza-create failed for $STANZA"
  if ! backup info --output=json | grep -q '"label"'; then
    backup backup --type=full > /dev/null && echo "backup: first full backup of $STANZA taken" \
      || echo "backup: the first full backup of $STANZA failed"
  fi
  while sleep "${BACKUP_INTERVAL_SECONDS:-86400}"; do
    backup backup --type=full > /dev/null && echo "backup: full backup of $STANZA taken" \
      || echo "backup: the full backup of $STANZA failed"
  done
}

copy_certificates
watch_certificates &

if [ -n "$STANZA" ]; then
  backup_config "$TARGET/ca.pem"
  backup_loop &
  set -- -c archive_mode=on \
    -c "archive_command=pgbackrest --config=$BACKUP_CONF --stanza=$STANZA archive-push %p" \
    -c "archive_timeout=${ARCHIVE_TIMEOUT_SECONDS:-60}" "$@"
fi

exec docker-entrypoint.sh postgres \
  -c ssl=on \
  -c ssl_cert_file="$TARGET/cert.pem" \
  -c ssl_key_file="$TARGET/key.pem" \
  -c ssl_ca_file="$TARGET/ca.pem" \
  -c ssl_min_protocol_version=TLSv1.2 \
  -c hba_file=/etc/freightflow/postgres/pg_hba.conf \
  "$@"
