#!/bin/sh
# Redis with TLS from the internal CA (ADR 0039), as the gateway's rate limiter runs it.
#
# - The only TCP port is 6379, TLS 1.2 or 1.3 (--port 0 closes plaintext), with the certificate the agent issues to
#   "redis". Clients verify it by name; they authenticate with no certificate (tls-auth-clients no).
# - A Unix socket inside the container (0700, Redis's own user) serves the healthcheck and the reload below; nothing
#   outside the container can reach it.
# - When the agent renews the certificate, the new files are copied into the other of two directories and Redis is
#   pointed at them with CONFIG SET, which reloads its TLS context: new connections get the new certificate, open ones
#   keep theirs. Two directories, so that every renewal changes the configured path.
set -eu

SOURCE="${TLS_SOURCE:-/tls}"
TARGET="${TLS_TARGET:-/data/tls}"
CHECK="${TLS_CHECK_SECONDS:-60}"
SOCKET=/tmp/redis.sock

# copy_to <a|b>: the agent's certificate and key, as files Redis's user owns.
copy_to() {
  mkdir -p "$TARGET/$1"
  chmod 700 "$TARGET" "$TARGET/$1"
  for file in cert.pem key.pem; do
    cp "$SOURCE/$file" "$TARGET/$1/$file.new"
    chmod 600 "$TARGET/$1/$file.new"
    mv -f "$TARGET/$1/$file.new" "$TARGET/$1/$file"
  done
}

watch_certificate() {
  slot=a
  while sleep "$CHECK"; do
    cmp -s "$SOURCE/cert.pem" "$TARGET/$slot/cert.pem" && continue
    if [ "$slot" = a ]; then next=b; else next=a; fi
    copy_to "$next"
    if out="$(redis-cli -s "$SOCKET" CONFIG SET tls-cert-file "$TARGET/$next/cert.pem" \
        tls-key-file "$TARGET/$next/key.pem" 2>&1)" && [ "$out" = OK ]; then
      slot="$next"
      echo "redis tls: certificate renewed, now serving $TARGET/$slot"
    else
      echo "redis tls: CONFIG SET failed, retrying in ${CHECK}s: $out"
    fi
  done
}

copy_to a
watch_certificate &

exec redis-server \
  --port 0 \
  --tls-port 6379 \
  --tls-cert-file "$TARGET/a/cert.pem" \
  --tls-key-file "$TARGET/a/key.pem" \
  --tls-ca-cert-file "$SOURCE/ca.pem" \
  --tls-auth-clients no \
  --tls-protocols "TLSv1.2 TLSv1.3" \
  --unixsocket "$SOCKET" \
  --unixsocketperm 700 \
  --save "" \
  --appendonly no \
  "$@"
