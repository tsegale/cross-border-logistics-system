#!/bin/sh
# The certificate agent (ADR 0039): issues every identity in identities.conf a certificate from the internal CA, and
# keeps it fresh. Runs in the step-cli image as its non-root user, with the consumers' groups added (group_add).
#
#   /tls/<identity>/cert.pem   the leaf and the intermediate, 0644
#   /tls/<identity>/key.pem    its key, 0640, group = the consumer's gid
#   /tls/<identity>/ca.pem     the root, 0644 (also /tls/ca.pem)
#
# A certificate is renewed (mTLS to the CA with the current one) once two thirds of its 24 h lifetime are gone, and
# issued afresh with the provisioner when it is missing or already expired (a laptop that slept through a renewal).
# New files are written next to the old ones and renamed into place, key first, so a reader sees a whole file; Spring
# Boot's SSL bundles, Keycloak, the collector, Postgres, Kafka and Redis pick them up without a restart.
set -eu

CA_URL="${CA_URL:-https://ca:9000}"
ROOT="${CA_ROOT:-/ca/root_ca.crt}"
PROVISIONER="${CA_PROVISIONER:-freightflow}"
PASSWORD_FILE="${CA_PASSWORD_FILE:-/run/secrets/ca_password}"
IDENTITIES="${IDENTITIES:-/etc/freightflow/tls/identities.conf}"
OUT="${TLS_DIR:-/tls}"
INTERVAL="${CHECK_INTERVAL_SECONDS:-300}"
LIFETIME="${CERT_LIFETIME:-24h}"

log() { echo "cert-agent: $*"; }

wait_for_ca() {
  for _ in $(seq 1 60); do
    if [ -s "$ROOT" ] && step ca health --ca-url "$CA_URL" --root "$ROOT" > /dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  log "the CA at $CA_URL did not become healthy"
  return 1
}

# issue <identity> <gid> <sans>: a new certificate with the provisioner's one-time token.
issue() {
  name="$1"; gid="$2"; sans="$3"; dir="$OUT/$name"
  mkdir -p "$dir"
  set -- "$name" "$dir/cert.pem.new" "$dir/key.pem.new" --ca-url "$CA_URL" --root "$ROOT" \
    --provisioner "$PROVISIONER" --provisioner-password-file "$PASSWORD_FILE" --not-after "$LIFETIME" \
    --kty EC --curve P-256 --force
  old_ifs="$IFS"; IFS=,
  for san in $sans; do set -- "$@" --san "$san"; done
  IFS="$old_ifs"
  out="$(step ca certificate "$@" 2>&1)" || { log "$out"; return 1; }
  install_new "$name" "$gid"
  log "issued $name ($(expiry "$dir/cert.pem"))"
}

# renew <identity> <gid>: the CA renews a certificate on the strength of the current one, over mTLS.
renew() {
  name="$1"; gid="$2"; dir="$OUT/$name"
  cp "$dir/key.pem" "$dir/key.pem.new"
  out="$(step ca renew "$dir/cert.pem" "$dir/key.pem.new" --out "$dir/cert.pem.new" --ca-url "$CA_URL" \
    --root "$ROOT" --force 2>&1)" || { log "$out"; return 1; }
  install_new "$name" "$gid"
  log "renewed $name ($(expiry "$dir/cert.pem"))"
}

install_new() {
  name="$1"; gid="$2"; dir="$OUT/$name"
  chgrp "$gid" "$dir/key.pem.new"
  chmod 0640 "$dir/key.pem.new"
  chmod 0644 "$dir/cert.pem.new"
  cp "$ROOT" "$dir/ca.pem.new" && chmod 0644 "$dir/ca.pem.new"
  mv -f "$dir/key.pem.new" "$dir/key.pem"
  mv -f "$dir/cert.pem.new" "$dir/cert.pem"
  mv -f "$dir/ca.pem.new" "$dir/ca.pem"
}

expiry() {
  step certificate inspect "$1" --short | sed -n 's/^ *to: *\(.*\)$/until \1/p' | head -1
}

expired() {
  ! step certificate verify "$1" --roots "$ROOT" > /dev/null 2>&1
}

check_all() {
  # The loop runs in a subshell (a pipe): it reports a failure through this file, overwritten on every pass.
  outcome="$OUT/.outcome"
  echo ok > "$outcome"
  grep -v '^[[:space:]]*#' "$IDENTITIES" | grep -v '^[[:space:]]*$' | while read -r name gid sans; do
    cert="$OUT/$name/cert.pem"
    if [ ! -s "$cert" ] || [ ! -s "$OUT/$name/key.pem" ] || expired "$cert"; then
      issue "$name" "$gid" "$sans" || { log "could not issue $name"; echo failed > "$outcome"; }
    elif step certificate needs-renewal "$cert" ${RENEW_WITHIN:+--expires-in "$RENEW_WITHIN"} > /dev/null 2>&1; then
      renew "$name" "$gid" || issue "$name" "$gid" "$sans" || { log "could not renew $name"; echo failed > "$outcome"; }
    fi
  done
  cp "$ROOT" "$OUT/ca.pem.new" && chmod 0644 "$OUT/ca.pem.new" && mv -f "$OUT/ca.pem.new" "$OUT/ca.pem"
  if [ "$(cat "$outcome")" != ok ]; then
    return 1
  fi
  date -u +%s > "$OUT/.checked"
}

wait_for_ca
check_all
log "every certificate issued; checking every ${INTERVAL}s"
while true; do
  sleep "$INTERVAL"
  check_all || log "a certificate could not be renewed; retrying in ${INTERVAL}s"
done
