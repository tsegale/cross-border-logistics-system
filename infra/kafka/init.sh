#!/usr/bin/env bash
# Runs once per `docker compose up`, after the broker is healthy and before any service starts (ADR 0022):
#   1. creates or updates each service's SCRAM-SHA-512 credential from /run/secrets/kafka_<service>_password
#      (so a rotated password or a new service needs no fresh volume),
#   2. grants exactly what infra/kafka/acls.conf lists.
# Granting is additive and idempotent. Removing a line does not revoke what was granted; revoke with kafka-acls.sh
# --remove, as the runbook in ADR 0022 says.
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
ACLS="${KAFKA_ACLS:-/etc/freightflow/kafka/acls.conf}"
BIN=/opt/kafka/bin
ADMIN_CONFIG="$(mktemp)"
trap 'rm -f "$ADMIN_CONFIG"' EXIT
cat > "$ADMIN_CONFIG" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="$(cat /run/secrets/kafka_admin_password)";
EOF

# The broker's healthcheck is a TCP connect; wait here until it answers an authenticated request.
for attempt in $(seq 1 60); do
  if "$BIN/kafka-broker-api-versions.sh" --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN_CONFIG" > /dev/null 2>&1; then
    break
  fi
  [[ $attempt -eq 60 ]] && { echo "kafka-init: broker at ${BOOTSTRAP} not answering" >&2; exit 1; }
  sleep 2
done

for service in banking logistics notification; do
  secret="/run/secrets/kafka_${service}_password"
  if [[ ! -r "$secret" ]]; then
    echo "kafka-init: no ${secret}, skipping user ${service}"
    continue
  fi
  "$BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN_CONFIG" --alter \
    --entity-type users --entity-name "$service" \
    --add-config "SCRAM-SHA-512=[iterations=8192,password=$(cat "$secret")]" > /dev/null
  echo "kafka-init: user ${service}"
done

declare -A OWNED CONSUMED CONSUMER_GROUP
while read -r kind topic owner consumers; do
  [[ -z "${kind}" || "${kind}" == \#* ]] && continue
  [[ "${kind}" == topic ]] || { echo "kafka-init: unknown line '${kind} ${topic}'" >&2; exit 1; }
  OWNED[$owner]+=" --topic ${topic}"
  for consumer in ${consumers}; do
    principal="${consumer%%:*}"
    CONSUMED[$principal]+=" --topic ${topic}"
    CONSUMER_GROUP[$principal]="${consumer#*:}"
  done
done < "$ACLS"

acl() {
  # shellcheck disable=SC2068
  "$BIN/kafka-acls.sh" --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN_CONFIG" --add $@ > /dev/null
}

for owner in "${!OWNED[@]}"; do
  # shellcheck disable=SC2086
  acl --allow-principal "User:${owner}" ${OWNED[$owner]} \
    --operation Create --operation Describe --operation DescribeConfigs --operation AlterConfigs --operation Write
  echo "kafka-init: ${owner} owns${OWNED[$owner]//--topic/}"
done
for consumer in "${!CONSUMED[@]}"; do
  # shellcheck disable=SC2086
  acl --allow-principal "User:${consumer}" ${CONSUMED[$consumer]} --operation Read --operation Describe
  acl --allow-principal "User:${consumer}" --group "${CONSUMER_GROUP[$consumer]}" --operation Read --operation Describe
  echo "kafka-init: ${consumer} reads${CONSUMED[$consumer]//--topic/} as group ${CONSUMER_GROUP[$consumer]}"
done
echo "kafka-init: done"
