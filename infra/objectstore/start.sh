#!/bin/sh
# The object store the databases back up to (ADR 0046): SeaweedFS 4.55 in one process, its S3 gateway the only
# listener on the network, with HTTPS from the agent's certificate (ADR 0039, 0040). Its master, volume server and
# filer bind to the container's loopback, which no other container can reach.
#
# One S3 identity, the backups' (secrets/backup_s3_*), may read, write and list, nothing else. The bucket is created
# once, through SeaweedFS's shell on the loopback. SeaweedFS reads its certificate at start: when the agent renews
# it, this restarts the server. An archive-push in that second fails and Postgres retries it, so no WAL is lost.
set -eu

SOURCE="${TLS_SOURCE:-/tls}"
CHECK="${TLS_CHECK_SECONDS:-60}"
WORK=/tmp/objectstore
BUCKET="${BACKUP_S3_BUCKET:-freightflow-backups}"
DATA="${OBJECTSTORE_DIR:-/data/objectstore}"

mkdir -p "$WORK" "$DATA"
umask 077
cat > "$WORK/s3.json" <<EOF
{"identities": [{"name": "backups",
  "credentials": [{"accessKey": "$(cat /run/secrets/backup_s3_access_key)",
                   "secretKey": "$(cat /run/secrets/backup_s3_secret_key)"}],
  "actions": ["Read", "Write", "List"]}]}
EOF
umask 022

pid=""
stop() {
  [ -n "$pid" ] && kill "$pid" 2> /dev/null && wait "$pid" 2> /dev/null
  exit 0
}
trap stop TERM INT

while true; do
  cp "$SOURCE/cert.pem" "$WORK/cert.pem"
  cp "$SOURCE/key.pem" "$WORK/key.pem"
  chmod 0600 "$WORK/key.pem"
  weed server -ip=127.0.0.1 -ip.bind=127.0.0.1 -dir="$DATA" -master.volumeSizeLimitMB=256 -volume.max=0 \
    -filer -s3 -s3.ip.bind=0.0.0.0 -s3.port=8333 -s3.config="$WORK/s3.json" \
    -s3.cert.file="$WORK/cert.pem" -s3.key.file="$WORK/key.pem" &
  pid=$!
  # The bucket, once: listing first, so a restart neither fails nor makes it twice.
  for _ in $(seq 60); do
    if echo "s3.bucket.list" | weed shell -master=127.0.0.1:9333 2> /dev/null | grep -q "^  $BUCKET"; then
      break
    fi
    echo "s3.bucket.create -name $BUCKET" | weed shell -master=127.0.0.1:9333 > /dev/null 2>&1 || true
    sleep 2
  done
  while sleep "$CHECK" & wait $!; do
    kill -0 "$pid" 2> /dev/null || { echo "objectstore: seaweedfs exited"; exit 1; }
    cmp -s "$SOURCE/cert.pem" "$WORK/cert.pem" || break
  done
  echo "objectstore tls: certificate renewed, restarting SeaweedFS with it"
  kill "$pid" 2> /dev/null || true
  wait "$pid" 2> /dev/null || true
done
