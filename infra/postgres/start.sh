#!/bin/sh
# Postgres with TLS from the internal CA (ADR 0039), for every database container.
#
# The agent's key is 0640 in the postgres group, which Postgres refuses ("private key file has group or world
# access"): this copies it, with the certificate and the root, to 0600 files the server owns, then starts Postgres
# with TLS required (pg_hba.conf: hostssl only). When the agent renews the certificate, the copies are refreshed and
# the configuration reloaded: Postgres re-reads its certificate and key on a reload, and connections already open
# keep the old one.
set -eu

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

copy_certificates
watch_certificates &

exec docker-entrypoint.sh postgres \
  -c ssl=on \
  -c ssl_cert_file="$TARGET/cert.pem" \
  -c ssl_key_file="$TARGET/key.pem" \
  -c ssl_ca_file="$TARGET/ca.pem" \
  -c ssl_min_protocol_version=TLSv1.2 \
  -c hba_file=/etc/freightflow/postgres/pg_hba.conf \
  "$@"
