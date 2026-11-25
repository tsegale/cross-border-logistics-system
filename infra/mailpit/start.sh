#!/bin/sh
# Mailpit with the agent's certificate (ADR 0039, ADR 0040): SMTP takes STARTTLS and refuses mail without it, and the
# web UI and API take HTTPS. SMTP still needs the login from secrets/smtp_password, now only inside TLS.
#
# Mailpit reads its certificate once, when it starts. When the agent renews it, this restarts Mailpit with the new one;
# what it holds is in a database file, so the restart loses nothing. A sender that connects during the second of the
# restart is refused and retries (notification's outbox, Alertmanager's queue).
set -eu

SOURCE="${TLS_SOURCE:-/tls}"
CHECK="${TLS_CHECK_SECONDS:-60}"
SERVING=/tmp/serving-cert.pem

MP_SMTP_AUTH="freightflow:$(cat /run/secrets/smtp_password)"
export MP_SMTP_AUTH
export MP_SMTP_TLS_CERT="$SOURCE/cert.pem" MP_SMTP_TLS_KEY="$SOURCE/key.pem" MP_SMTP_REQUIRE_STARTTLS=true
export MP_UI_TLS_CERT="$SOURCE/cert.pem" MP_UI_TLS_KEY="$SOURCE/key.pem"
export MP_DATABASE="${MP_DATABASE:-/tmp/mailpit.db}"

pid=""
stop() {
  [ -n "$pid" ] && kill "$pid" 2> /dev/null && wait "$pid" 2> /dev/null
  exit 0
}
trap stop TERM INT

while true; do
  cp "$SOURCE/cert.pem" "$SERVING"
  /mailpit &
  pid=$!
  # sleep in the background and wait on it, so that compose's SIGTERM is handled at once.
  while sleep "$CHECK" & wait $!; do
    kill -0 "$pid" 2> /dev/null || { echo "mailpit tls: mailpit exited"; exit 1; }
    cmp -s "$SOURCE/cert.pem" "$SERVING" || break
  done
  echo "mailpit tls: certificate renewed, restarting Mailpit with it"
  kill "$pid" 2> /dev/null || true
  wait "$pid" 2> /dev/null || true
done
