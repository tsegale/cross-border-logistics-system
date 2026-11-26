#!/bin/sh
# pgBackRest for one database (ADR 0046), sourced by start.sh and restore.sh.
#
# The repository is the object store's S3 bucket, over TLS verified against the internal CA. Each stanza has its
# own path in it. Every backup and archived WAL segment is encrypted with the backup passphrase, so the object
# store holds nothing readable. The configuration, credentials included, lives on the container's tmpfs, 0600,
# never on its image or its volume.

STANZA="${PGBACKREST_STANZA:-}"
BACKUP_DIR=/tmp/pgbackrest
BACKUP_CONF="$BACKUP_DIR/pgbackrest.conf"

# backup_config <root CA file>: writes the configuration; does nothing without a stanza.
backup_config() {
  [ -n "$STANZA" ] || return 0
  mkdir -p "$BACKUP_DIR/lock" "$BACKUP_DIR/log" "$BACKUP_DIR/spool"
  umask 077
  cat > "$BACKUP_CONF" <<EOF
[global]
repo1-type=s3
repo1-s3-endpoint=${BACKUP_S3_ENDPOINT:-objectstore}
repo1-s3-port=${BACKUP_S3_PORT:-8333}
repo1-s3-bucket=${BACKUP_S3_BUCKET:-freightflow-backups}
repo1-s3-region=${BACKUP_S3_REGION:-us-east-1}
repo1-s3-uri-style=path
repo1-s3-verify-tls=y
repo1-s3-ca-file=$1
repo1-s3-key=$(cat /run/secrets/backup_s3_access_key)
repo1-s3-key-secret=$(cat /run/secrets/backup_s3_secret_key)
repo1-path=/$STANZA
repo1-cipher-type=aes-256-cbc
repo1-cipher-pass=$(cat /run/secrets/backup_cipher_pass)
repo1-retention-full=${BACKUP_RETENTION_FULL:-2}
lock-path=$BACKUP_DIR/lock
log-path=$BACKUP_DIR/log
spool-path=$BACKUP_DIR/spool
log-level-console=info
log-level-file=off
start-fast=y
compress-type=zst

[$STANZA]
pg1-path=$PGDATA
pg1-socket-path=/var/run/postgresql
pg1-user=$POSTGRES_USER
EOF
  umask 022
}

backup() {
  pgbackrest --config="$BACKUP_CONF" --stanza="$STANZA" "$@"
}
