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

# Each admin call is retried: on a loaded host (a laptop starting the whole stack) a call can time out once, and
# kafka-init used to exit 1 with nothing logged. Both operations are idempotent, so a retry is safe. The command's own
# output is shown on failure, never the command line, which can hold a password.
retrying() {
  local what="$1" out attempt
  shift
  for attempt in 1 2 3 4 5; do
    if out="$("$@" 2>&1)"; then
      return 0
    fi
    echo "kafka-init: ${what} failed (attempt ${attempt} of 5): ${out}" >&2
    sleep $((attempt * 3))
  done
  echo "kafka-init: ${what} failed 5 times, giving up" >&2
  return 1
}

for service in banking logistics notification monitoring; do
  secret="/run/secrets/kafka_${service}_password"
  if [[ ! -r "$secret" ]]; then
    echo "kafka-init: no ${secret}, skipping user ${service}"
    continue
  fi
  retrying "user ${service}" "$BIN/kafka-configs.sh" --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN_CONFIG" \
    --alter --entity-type users --entity-name "$service" \
    --add-config "SCRAM-SHA-512=[iterations=8192,password=$(cat "$secret")]"
  echo "kafka-init: user ${service}"
done

# A name ending in * is a prefix: it covers every topic or group that starts with it (retry topics, ADR 0026).
declare -A OWNED_LITERAL OWNED_PREFIXED READ_LITERAL READ_PREFIXED GROUP_LITERAL GROUP_PREFIXED
pattern() { [[ "$1" == *\* ]] && echo PREFIXED || echo LITERAL; }
while read -r kind topic owner consumers; do
  [[ -z "${kind}" || "${kind}" == \#* ]] && continue
  [[ "${kind}" == topic ]] || { echo "kafka-init: unknown line '${kind} ${topic}'" >&2; exit 1; }
  declare -n owned="OWNED_$(pattern "$topic")" readers="READ_$(pattern "$topic")"
  owned[$owner]+=" --topic ${topic%\*}"
  for consumer in ${consumers}; do
    principal="${consumer%%:*}"
    group="${consumer#*:}"
    readers[$principal]+=" --topic ${topic%\*}"
    declare -n groups="GROUP_$(pattern "$group")"
    [[ " ${groups[$principal]:-} " == *" --group ${group%\*} "* ]] || groups[$principal]+=" --group ${group%\*}"
    unset -n groups
  done
  unset -n owned readers
done < "$ACLS"

acl() {
  # shellcheck disable=SC2068
  retrying "acl" "$BIN/kafka-acls.sh" --bootstrap-server "$BOOTSTRAP" --command-config "$ADMIN_CONFIG" --add $@
}

for type in LITERAL PREFIXED; do
  declare -n owned="OWNED_${type}" readers="READ_${type}" groups="GROUP_${type}"
  pattern_type="--resource-pattern-type ${type,,}"
  for owner in "${!owned[@]}"; do
    # shellcheck disable=SC2086
    acl ${pattern_type} --allow-principal "User:${owner}" ${owned[$owner]}       --operation Create --operation Describe --operation DescribeConfigs --operation AlterConfigs --operation Write
    echo "kafka-init: ${owner} owns (${type,,})${owned[$owner]//--topic/}"
  done
  for consumer in "${!readers[@]}"; do
    # shellcheck disable=SC2086
    acl ${pattern_type} --allow-principal "User:${consumer}" ${readers[$consumer]} --operation Read --operation Describe
    echo "kafka-init: ${consumer} reads (${type,,})${readers[$consumer]//--topic/}"
  done
  for consumer in "${!groups[@]}"; do
    # shellcheck disable=SC2086
    acl ${pattern_type} --allow-principal "User:${consumer}" ${groups[$consumer]} --operation Read --operation Describe
    echo "kafka-init: ${consumer} uses groups (${type,,})${groups[$consumer]//--group/}"
  done
  unset -n owned readers groups
done
# The observability collector's user (ADR 0031): describes the cluster, every topic and every group, so it can
# read consumer lag and topic offsets. It reads no record and writes nothing.
if [[ -r /run/secrets/kafka_monitoring_password ]]; then
  # acl() expands its arguments unquoted: without noglob the shell would turn '*' into file names.
  set -f
  acl --allow-principal User:monitoring --cluster --operation Describe
  acl --allow-principal User:monitoring --topic '*' --operation Describe --operation DescribeConfigs
  acl --allow-principal User:monitoring --group '*' --operation Describe
  echo "kafka-init: monitoring describes the cluster, every topic and every group"
  set +f
fi
echo "kafka-init: done"
