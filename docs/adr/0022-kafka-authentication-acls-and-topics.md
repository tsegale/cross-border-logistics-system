# 22. Kafka authentication, ACLs and topic ownership

Date: 2026-11-20 · Status: Accepted · Moves ADR 0003's Kafka into the default stack

## Context
Up to Phase 3 Kafka ran behind `--profile events` with three PLAINTEXT listeners, one of them published on the host,
and no authorizer. Anything that reached the port could read every topic and write any event. Once banking and
logistics publish money and shipment events (Phase 4), a forged `hold.captured` or `journal-entry.posted` is as
dangerous as a forged API call: consumers act on it. The threat model named the open broker as an accepted gap.

Two requirements follow. Every client proves who it is, and each identity may write only its own topics and read
only what it consumes (least privilege, as ADR 0018 does for HTTP).

## Options
**Authentication**
1. **SASL/SCRAM-SHA-512.** A username and a salted, iterated password, verified with a challenge, so the password
   never crosses the wire. In KRaft the credentials live in the cluster metadata: `kafka-storage format --add-scram`
   (KIP-900, Kafka 3.5) writes the first ones before the first start, and `kafka-configs --alter` adds or rotates
   them on a running broker. Clients need one secret file each, like the database passwords (ADR 0019).
2. **mTLS.** Each client presents a certificate; the principal is its subject. Strong, and it encrypts the traffic,
   but it needs a CA, a keystore and truststore per service, expiry and rotation. Nothing else on the compose
   network uses TLS yet (Postgres, service-to-service HTTP), so it would be the one encrypted hop among plaintext
   ones.
3. **SASL/OAUTHBEARER with Keycloak.** One identity system for HTTP and Kafka. But Kafka would depend on Keycloak
   being up to accept a connection, the services' client credentials would gain a second audience, and the broker's
   OAuth validation needs its own JWKS configuration and testing: more moving parts for the same guarantee.

**Authorisation**
- `StandardAuthorizer` (KRaft's built-in, ACLs in the metadata log) with `allow.everyone.if.no.acl.found=false`, so
  anything not granted is refused. No alternative is worth the dependency.

## Decision
- **SASL/SCRAM-SHA-512** on the one client listener (`INTERNAL`, `SASL_PLAINTEXT`), one user per service:
  `banking`, `logistics`, `notification`, plus `admin` for the broker and its init job. Passwords are generated into
  `secrets/` by `scripts/generate_secrets.py` and mounted as compose secrets; each service mounts only its own, under
  the name `kafka_password`, and refuses to start without it (`RequiredSecrets`).
- **The broker starts from `infra/kafka/start.sh`**, not the image's entrypoint, whose storage format cannot add
  SCRAM credentials. It writes the broker configuration, formats the storage with the `admin` user
  (`--ignore-formatted` keeps an existing volume), and starts Kafka.
- **`kafka-init`**, a one-shot compose service, waits for an authenticated answer, creates or updates the service
  users from their secrets, and grants what `infra/kafka/acls.conf` lists. Services start only after it has
  completed (`service_completed_successfully`). It runs on every `docker compose up`, so a rotated password or a new
  line in `acls.conf` needs no fresh volume.
- **ACLs, declared in one file.** The owner of a topic gets Create, Describe, DescribeConfigs, AlterConfigs and
  Write on it, by literal name; each consumer gets Read and Describe on the topic and Read on its own consumer group.
  Nobody but `admin` has a cluster-wide permission.

| Topic | Owner (writes) | Consumers (group) |
| --- | --- | --- |
| `freightflow.account.v1` | banking | |
| `freightflow.journal-entry.v1` | banking | notification (`notification-service`) |
| `freightflow.hold.v1` | banking | logistics (`logistics-service`), notification (`notification-service`) |
| `freightflow.approval.v1` | banking | |
| `freightflow.shipment.v1` | logistics | notification (`notification-service`) |

- **The controller listener is PLAINTEXT on the container's loopback (`127.0.0.1:9093`)**, and `User:ANONYMOUS` is a
  super user. In a combined broker+controller node the broker registers with its own controller over that listener
  as ANONYMOUS; without the grant it cannot register (`CLUSTER_AUTHORIZATION_FAILED`, verified on 4.3.1). No other
  container can reach a loopback listener, so ANONYMOUS is only ever the broker itself. A multi-node cluster puts the
  controllers on their own SASL or mTLS listener instead.
- **Topics are declared in code by their owner** (`EventTopics`, Spring's `KafkaAdmin` with `NewTopic` beans),
  `freightflow.<aggregate>.v<n>`, keyed by aggregate id, never auto-created. Per topic:
  - partitions: 6 for the busy streams (journal entries, holds, shipments), 3 for rare ones (accounts, approvals).
    Measured peaks are tens of events per second, far below one partition's capacity; the count bounds how many
    instances of a consumer group can share the work, and raising it later reshuffles keys, so it has headroom;
  - retention 30 days with `cleanup.policy=delete`: a consumer that was down, or a new one, catches up for a month,
    longer than any hold lives. These are facts, not the latest state of a key, so compaction would lose them. Kafka
    is not the system of record; the databases are;
  - replication from `KAFKA_REPLICATION_FACTOR` (1 locally), `min.insync.replicas` 2 when it is 3 or more.
- **Nothing is published.** The host listener is gone (ADR 0019: only the gateway and Keycloak publish a port). For
  local inspection, `docker compose exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:29092
  --command-config <admin properties> ...`.

## Consequences
- An unauthenticated client is dropped during the SASL handshake; a wrong password fails with
  `SaslAuthenticationException`; logistics writing banking's `hold.v1` fails with `TopicAuthorizationException`
  (`KafkaAccessIntegrationTest`, `EventTopicsIntegrationTest`). The tests run the same `start.sh` and `init.sh`
  against the pinned image, so the compose broker and the tested broker are configured by the same code.
- Traffic on the compose network is not encrypted. SCRAM protects the password, not the events. Encrypting
  in-cluster traffic (SASL_SSL here, TLS for Postgres and service HTTP) is one deployment decision for all of them,
  not a Kafka-only one, and it belongs with the production deployment.
- `kafka-init` only adds. Removing a line from `acls.conf` does not revoke the grant; revoke with
  `kafka-acls.sh --remove --allow-principal User:<service> --topic <topic> --operation <op>`. Reconciling would need
  the list of every ACL the broker holds and a window with fewer grants than intended on each start.
- SCRAM iterations are 8192 for the service users. Authentication happens once per connection, so the cost is paid
  at startup, not per event.
- A service starts without the broker: `KafkaAdmin` waits at most `spring.kafka.admin.operation-timeout` (10 s) and
  carries on. Its topics then exist only once it, or the outbox relay (Phase 4, §3), declares them again.
- The broker adds `kafka` (heap capped at 512 MB) and the short-lived `kafka-init` to the default stack; the stack's
  measured memory is in the README.
