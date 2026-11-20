#!/bin/sh
# Debezium Server for the CDC benchmark (benchmarks/compose.cdc.yml). Configured through environment variables, which
# Debezium Server maps to its properties (DEBEZIUM_SOURCE_SLOT_NAME is debezium.source.slot.name); the passwords come
# from the compose secrets, so none is written into a file or the compose file.
set -eu

KAFKA_PASSWORD="$(cat /run/secrets/kafka_admin_password)"
DB_PASSWORD="$(cat /run/secrets/logistics_db_password)"

# Source: logistics' database, only the outbox table, new rows from the start of the slot (no snapshot).
export DEBEZIUM_SOURCE_CONNECTOR_CLASS=io.debezium.connector.postgresql.PostgresConnector
export DEBEZIUM_SOURCE_DATABASE_HOSTNAME=logistics-db
export DEBEZIUM_SOURCE_DATABASE_PORT=5432
export DEBEZIUM_SOURCE_DATABASE_USER="$LOGISTICS_DB_USER"
export DEBEZIUM_SOURCE_DATABASE_PASSWORD="$DB_PASSWORD"
export DEBEZIUM_SOURCE_DATABASE_DBNAME=logistics
export DEBEZIUM_SOURCE_TOPIC_PREFIX=logistics
export DEBEZIUM_SOURCE_PLUGIN_NAME=pgoutput
export DEBEZIUM_SOURCE_SLOT_NAME=freightflow_benchmark
export DEBEZIUM_SOURCE_PUBLICATION_NAME=freightflow_benchmark
export DEBEZIUM_SOURCE_PUBLICATION_AUTOCREATE_MODE=filtered
export DEBEZIUM_SOURCE_TABLE_INCLUDE_LIST=public.event_outbox
export DEBEZIUM_SOURCE_SNAPSHOT_MODE=no_data
export DEBEZIUM_SOURCE_OFFSET_STORAGE_FILE_FILENAME=/tmp/offsets.dat
export DEBEZIUM_SOURCE_OFFSET_FLUSH_INTERVAL_MS=1000

# The outbox event router: one record per inserted row, keyed by the aggregate, the envelope as the value, to the
# shadow topic. The relay's updates (published_at) and the pruner's deletes are not events and are skipped.
export DEBEZIUM_TRANSFORMS=outbox
export DEBEZIUM_TRANSFORMS_OUTBOX_TYPE=io.debezium.transforms.outbox.EventRouter
export DEBEZIUM_TRANSFORMS_OUTBOX_TABLE_FIELD_EVENT_ID=id
export DEBEZIUM_TRANSFORMS_OUTBOX_TABLE_FIELD_EVENT_KEY=aggregate_id
export DEBEZIUM_TRANSFORMS_OUTBOX_TABLE_FIELD_EVENT_PAYLOAD=envelope
export DEBEZIUM_TRANSFORMS_OUTBOX_ROUTE_BY_FIELD=topic
export DEBEZIUM_TRANSFORMS_OUTBOX_ROUTE_TOPIC_REPLACEMENT=freightflow.shipment.v1.cdc
export DEBEZIUM_TRANSFORMS_OUTBOX_TABLE_OP_INVALID_BEHAVIOR=debug

# Sink: the broker, idempotent with acks=all like the relay (ADR 0025).
export DEBEZIUM_SINK_TYPE=kafka
export DEBEZIUM_SINK_KAFKA_PRODUCER_BOOTSTRAP_SERVERS=kafka:29092
export DEBEZIUM_SINK_KAFKA_PRODUCER_KEY_SERIALIZER=org.apache.kafka.common.serialization.StringSerializer
export DEBEZIUM_SINK_KAFKA_PRODUCER_VALUE_SERIALIZER=org.apache.kafka.common.serialization.StringSerializer
export DEBEZIUM_SINK_KAFKA_PRODUCER_ACKS=all
export DEBEZIUM_SINK_KAFKA_PRODUCER_ENABLE_IDEMPOTENCE=true
export DEBEZIUM_SINK_KAFKA_PRODUCER_COMPRESSION_TYPE=lz4
export DEBEZIUM_SINK_KAFKA_PRODUCER_SECURITY_PROTOCOL=SASL_PLAINTEXT
export DEBEZIUM_SINK_KAFKA_PRODUCER_SASL_MECHANISM=SCRAM-SHA-512
export DEBEZIUM_SINK_KAFKA_PRODUCER_SASL_JAAS_CONFIG="org.apache.kafka.common.security.scram.ScramLoginModule required username=\"admin\" password=\"${KAFKA_PASSWORD}\";"

export DEBEZIUM_FORMAT_KEY=json
export DEBEZIUM_FORMAT_VALUE=json
export DEBEZIUM_FORMAT_KEY_SCHEMAS_ENABLE=false
export DEBEZIUM_FORMAT_VALUE_SCHEMAS_ENABLE=false

exec /debezium/run.sh
