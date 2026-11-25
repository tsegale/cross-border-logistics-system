#!/usr/bin/env bash
# Starts the single-node KRaft broker with SCRAM authentication and ACLs (ADR 0022). Replaces the image's own
# entrypoint, whose storage format cannot add SCRAM credentials.
#
# - Clients (the services, kafka-init) connect on INTERNAL, SASL_SSL with SCRAM-SHA-512: TLS 1.2 or 1.3 with the
#   certificate the internal CA issued to "kafka" (ADR 0039), and a SCRAM login inside it. Nothing else is open.
# - The certificate is renewed every 16 h. A watcher builds the new keystore and points the listeners at it with a
#   dynamic broker config (kafka-configs.sh), through a second file and back, so that every renewal is a real change
#   (watch_certificate): new connections get the new certificate, open ones keep theirs, nothing restarts.
# - The controller listener is PLAINTEXT on the container's loopback only: in a combined broker+controller node the
#   broker reaches its own controller there, and no other container can.
# - StandardAuthorizer with allow.everyone.if.no.acl.found=false: what infra/kafka/acls.conf does not grant is refused.
# - The admin user is written into the metadata log when the storage is first formatted (KIP-900, --add-scram);
#   kafka-init creates the service users and their ACLs through it on every start.
#
# Needs CLUSTER_ID, the secret /run/secrets/kafka_admin_password and the agent's files in /tls (cert.pem, key.pem as
# PKCS#8, ca.pem). KAFKA_EXTERNAL_ADVERTISED (host:port) adds a second SASL_SSL listener on 9094 for clients outside the container network: only the services' Testcontainers tests,
# which advertise the mapped port. Compose sets neither it nor a published port.
set -euo pipefail

: "${CLUSTER_ID:?set CLUSTER_ID}"
ADMIN_PASSWORD="$(cat /run/secrets/kafka_admin_password)"
CONFIG=/opt/kafka/config/freightflow.properties
DATA_DIR=/var/lib/kafka/data
TLS_SOURCE="${TLS_SOURCE:-/tls}"
TLS_DIR="${TLS_DIR:-/tmp/kafka-tls}"
TLS_CHECK="${TLS_CHECK_SECONDS:-60}"
ADMIN_CLIENT="${TLS_DIR}/admin.properties"
LISTENERS="INTERNAL://0.0.0.0:29092,CONTROLLER://127.0.0.1:9093"
ADVERTISED="INTERNAL://${KAFKA_ADVERTISED_HOST:-kafka}:29092"
PROTOCOLS="INTERNAL:SASL_SSL,CONTROLLER:PLAINTEXT"
TLS_LISTENERS="internal"
EXTERNAL_JAAS=""
if [[ -n "${KAFKA_EXTERNAL_ADVERTISED:-}" ]]; then
  LISTENERS="${LISTENERS},EXTERNAL://0.0.0.0:9094"
  ADVERTISED="${ADVERTISED},EXTERNAL://${KAFKA_EXTERNAL_ADVERTISED}"
  PROTOCOLS="${PROTOCOLS},EXTERNAL:SASL_SSL"
  TLS_LISTENERS="internal external"
  EXTERNAL_JAAS="listener.name.external.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;"
fi

# keystore <a|b>: the key and the chain in one PEM file, as Kafka's PEM keystore reads it.
keystore() {
  local file="${TLS_DIR}/keystore-$1.pem"
  cat "${TLS_SOURCE}/key.pem" "${TLS_SOURCE}/cert.pem" > "${file}.new"
  chmod 600 "${file}.new"
  mv -f "${file}.new" "$file"
}

mkdir -p "$TLS_DIR"
chmod 700 "$TLS_DIR"
# Both files exist from the start: after a restart the dynamic config may name either.
keystore a
keystore b
cp "${TLS_SOURCE}/cert.pem" "${TLS_DIR}/cert.pem"

listener_tls() {
  local listener
  for listener in $TLS_LISTENERS; do
    echo "listener.name.${listener}.ssl.keystore.type=PEM"
    echo "listener.name.${listener}.ssl.keystore.location=${TLS_DIR}/keystore-a.pem"
    echo "listener.name.${listener}.ssl.truststore.type=PEM"
    echo "listener.name.${listener}.ssl.truststore.location=${TLS_SOURCE}/ca.pem"
  done
}

cat > "$CONFIG" <<EOF
process.roles=broker,controller
node.id=1
controller.quorum.voters=1@127.0.0.1:9093
listeners=${LISTENERS}
advertised.listeners=${ADVERTISED}
listener.security.protocol.map=${PROTOCOLS}
inter.broker.listener.name=INTERNAL
controller.listener.names=CONTROLLER

sasl.enabled.mechanisms=SCRAM-SHA-512
sasl.mechanism.inter.broker.protocol=SCRAM-SHA-512
listener.name.internal.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="${ADMIN_PASSWORD}";
${EXTERNAL_JAAS}

# TLS 1.2 or 1.3 only. The broker's own connections to INTERNAL check its certificate's name like any client's.
ssl.enabled.protocols=TLSv1.3,TLSv1.2
ssl.protocol=TLSv1.3
ssl.endpoint.identification.algorithm=https
$(listener_tls)

authorizer.class.name=org.apache.kafka.metadata.authorizer.StandardAuthorizer
# admin: the broker itself and kafka-init. ANONYMOUS: only reachable on the loopback controller listener.
super.users=User:admin;User:ANONYMOUS
allow.everyone.if.no.acl.found=false

# Topics are declared by the services that own them (KafkaAdmin), never created by a typo in a consumer.
auto.create.topics.enable=false
log.dirs=${DATA_DIR}
num.partitions=1
default.replication.factor=1
offsets.topic.replication.factor=1
transaction.state.log.replication.factor=1
transaction.state.log.min.isr=1
group.initial.rebalance.delay.ms=0
EOF
chmod 600 "$CONFIG"

# --ignore-formatted: a restart with an existing volume keeps its metadata, users and ACLs included.
/opt/kafka/bin/kafka-storage.sh format --config "$CONFIG" --cluster-id "$CLUSTER_ID" --ignore-formatted \
  --add-scram "SCRAM-SHA-512=[name=admin,password=${ADMIN_PASSWORD}]"

# The watcher's admin login, over the same TLS as every client.
cat > "$ADMIN_CLIENT" <<EOF
security.protocol=SASL_SSL
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="${ADMIN_PASSWORD}";
ssl.truststore.type=PEM
ssl.truststore.location=${TLS_SOURCE}/ca.pem
EOF
chmod 600 "$ADMIN_CLIENT"

# use_keystore <a|b>: rebuild that file from the agent's current files and point every TLS listener at it.
use_keystore() {
  local updates="" listener
  keystore "$1"
  for listener in $TLS_LISTENERS; do
    updates="${updates:+${updates},}listener.name.${listener}.ssl.keystore.location=${TLS_DIR}/keystore-$1.pem"
  done
  # A second JVM inside the broker's memory limit for a few seconds: a small heap, no JIT tiers it does not need.
  KAFKA_HEAP_OPTS="-Xmx48m" KAFKA_JVM_PERFORMANCE_OPTS="-XX:+UseSerialGC -XX:TieredStopAtLevel=1" \
    /opt/kafka/bin/kafka-configs.sh --bootstrap-server "${KAFKA_ADVERTISED_HOST:-kafka}:29092" \
    --command-config "$ADMIN_CLIENT" --entity-type brokers --entity-name 1 --alter --add-config "$updates" 2>&1
}

# When the agent has renewed the certificate: b, then a. Kafka reloads a keystore only when the configured location
# changes, and after a restart the stored dynamic config may name either file; whichever it named, one of the two
# steps is a change, and the listeners end on a, the static value, with the new certificate. A failed update is
# retried on the next check; the old certificate is still valid for 8 h.
watch_certificate() {
  local out
  while sleep "$TLS_CHECK"; do
    cmp -s "${TLS_SOURCE}/cert.pem" "${TLS_DIR}/cert.pem" && continue
    if out="$(use_keystore b)" && out="$(use_keystore a)"; then
      cp "${TLS_SOURCE}/cert.pem" "${TLS_DIR}/cert.pem"
      echo "kafka tls: certificate renewed, listeners reloaded"
    else
      echo "kafka tls: the listener update failed, retrying in ${TLS_CHECK}s: ${out}"
    fi
  done
}
watch_certificate &

exec /opt/kafka/bin/kafka-server-start.sh "$CONFIG"
