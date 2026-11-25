#!/usr/bin/env bash
# Creates the CDC shadow topic (benchmarks/compose.cdc.yml) as the broker's admin.
set -euo pipefail

CONFIG="$(mktemp)"
trap 'rm -f "$CONFIG"' EXIT
cat > "$CONFIG" <<EOF
security.protocol=SASL_SSL
ssl.truststore.type=PEM
ssl.truststore.location=/tls/ca.pem
sasl.mechanism=SCRAM-SHA-512
sasl.jaas.config=org.apache.kafka.common.security.scram.ScramLoginModule required username="admin" password="$(cat /run/secrets/kafka_admin_password)";
EOF

/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092 --command-config "$CONFIG" --create --if-not-exists \
  --topic freightflow.shipment.v1.cdc --partitions 6 --replication-factor 1
echo "cdc-topic: freightflow.shipment.v1.cdc"
