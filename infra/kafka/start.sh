#!/usr/bin/env bash
# Starts the single-node KRaft broker with SCRAM authentication and ACLs (ADR 0022). Replaces the image's own
# entrypoint, whose storage format cannot add SCRAM credentials.
#
# - Clients (the services, kafka-init) connect on INTERNAL, SASL_PLAINTEXT with SCRAM-SHA-512. Nothing else is open.
# - The controller listener is PLAINTEXT on the container's loopback only: in a combined broker+controller node the
#   broker reaches its own controller there, and no other container can.
# - StandardAuthorizer with allow.everyone.if.no.acl.found=false: what infra/kafka/acls.conf does not grant is refused.
# - The admin user is written into the metadata log when the storage is first formatted (KIP-900, --add-scram);
#   kafka-init creates the service users and their ACLs through it on every start.
#
# Needs CLUSTER_ID and the secret /run/secrets/kafka_admin_password. KAFKA_EXTERNAL_ADVERTISED (host:port) adds a
# second SASL listener on 9094 for clients outside the container network: only the services' Testcontainers tests,
# which advertise the mapped port. Compose sets neither it nor a published port.
set -euo pipefail

: "${CLUSTER_ID:?set CLUSTER_ID}"
ADMIN_PASSWORD="$(cat /run/secrets/kafka_admin_password)"
CONFIG=/opt/kafka/config/freightflow.properties
DATA_DIR=/var/lib/kafka/data
LISTENERS="INTERNAL://0.0.0.0:29092,CONTROLLER://127.0.0.1:9093"
ADVERTISED="INTERNAL://${KAFKA_ADVERTISED_HOST:-kafka}:29092"
PROTOCOLS="INTERNAL:SASL_PLAINTEXT,CONTROLLER:PLAINTEXT"
EXTERNAL_JAAS=""
if [[ -n "${KAFKA_EXTERNAL_ADVERTISED:-}" ]]; then
  LISTENERS="${LISTENERS},EXTERNAL://0.0.0.0:9094"
  ADVERTISED="${ADVERTISED},EXTERNAL://${KAFKA_EXTERNAL_ADVERTISED}"
  PROTOCOLS="${PROTOCOLS},EXTERNAL:SASL_PLAINTEXT"
  EXTERNAL_JAAS="listener.name.external.scram-sha-512.sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required;"
fi

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

exec /opt/kafka/bin/kafka-server-start.sh "$CONFIG"
