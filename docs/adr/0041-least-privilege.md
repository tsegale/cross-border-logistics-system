# 41. Least privilege: database roles, containers, consoles

Date: 2026-11-26 · Status: Accepted · Builds on ADR 0019 (secrets), ADR 0022 (Kafka ACLs), ADR 0004 (ledger)

## Context
Every service connected to its database as the user the Postgres image creates from `POSTGRES_USER`: a superuser
that owns the schema. A bug or an injection in the service could alter the schema, read other roles' data or rewrite
the ledger's history; only the ledger's triggers stood in the way, and a superuser can drop a trigger. The collector
read statistics as the same users. The Phase 7 brief asks for owner and runtime roles with no UPDATE or DELETE on the
append-only tables.

## Sources, checked 2026-11-26
- PostgreSQL 17: `GRANT`, `ALTER DEFAULT PRIVILEGES`, the predefined role `pg_monitor`, `CREATE` on the `public` schema
  no longer granted to `PUBLIC` (since 15), an empty password in `ALTER ROLE` setting none.
- Flyway 12.11 (Spring Boot 4.2's managed version): SQL callbacks named `afterMigrate.sql` in a migration location run
  after every `migrate`; placeholders are replaced in plain SQL but left as they are inside a dollar-quoted body
  (shown by a probe against PostgreSQL 17 on 2026-11-26: the role's password came out as the placeholder's text).
- Spring Boot 4.2: `spring.flyway.user` and `password` give Flyway its own connection; `spring.flyway.placeholders`.

## Decision: three roles per database
| Role | Who | May |
| --- | --- | --- |
| owner (`POSTGRES_USER`) | Flyway, at the service's start | everything: the schema is its own |
| `<service>_app` | the service | `SELECT`, `INSERT`, `UPDATE`, `DELETE` on rows; no DDL, no Flyway history; less where a table is append-only |
| `monitoring` | the collector's postgresql receiver | `pg_monitor`: statistics, no rows |

- **Append-only tables lose `UPDATE` and `DELETE`** for the runtime role: banking's `journal_entries`, `postings`,
  `hold_extensions`, `system_balance_checkpoints`, `customs_settlements` (and `DELETE` on `reference_rates`, which are
  decided once); logistics' `tracking_events` and `dcsa_event_shipments`. Logistics' reviewed reference data
  (`tariffs`, `locations`, `customs_tariff_sources`, `customs_tariff_lines`) is `SELECT` only. The triggers stay: the
  role may not even try, and the owner is still stopped by them.
- **The roles are created by a migration** (`V13`, `V18`, `V5`), without a password. **`afterMigrate.sql` gives them
  their passwords on every start**, from the secrets `<service>_db_app_password` and `db_monitoring_password`
  (Flyway placeholders), as plain statements: inside a `DO` block the placeholders would stay unreplaced. A rotated
  secret needs no migration, and no password is in a migration or its history.
- **Default privileges** give a table a later migration creates the same rights; a new append-only table revokes them
  in its own migration (CLAUDE.md).
- **Tests run as the runtime role.** The shared Postgres container is no longer a service connection (which would log
  in as its superuser); the service connects as `<service>_app` and Flyway as the container's user, so a grant the
  code needs and a migration forgot fails a test. `DatabaseRolesIntegrationTest` in each service pins the roles' rights;
  the ledger's tests check both layers, the role's `permission denied` and the owner's trigger.

## Decision: every container hardened
Every service in `docker-compose.yml` merges one anchor, `x-hardened`: its root filesystem read-only, every Linux
capability dropped, `no-new-privileges`. What a container writes is a named volume (data) or a sized tmpfs (`/tmp`,
Postgres's socket directory and certificate copies, Keycloak's `data`, Grafana's database). Every one runs as a user
other than root; the images that defaulted to root are given one (Tempo, the collector and socat their images' own
non-root users, the partner simulator and Mailpit 1000).

- **The step-ca binary carries a file capability** (`cap_net_bind_service`, for port 443), and the kernel refuses to
  run a binary whose file capabilities it may not grant. The CA listens on 9000: `infra/ca/Dockerfile` removes the
  capability from the binary and changes nothing else.
- **No container holds the Docker socket but `docker-api`.** The collector read container memory through the socket
  as root, and a read-only mount does not limit the API: whoever has the socket can start a privileged container and
  own the host. `docker-api` (the official HAProxy image) holds it, as root because the socket is root's, with no
  capability, and passes on only `GET` for `/_ping`, `/version`, `/containers/json` and a container's `json` and
  `stats`, on a network only the collector shares. Everything else is a 403, so the collector cannot read a
  container's files or start one.
- **Checked on the running containers**, not on the file: `scripts/check_hardening.py` inspects every one (read-only
  root, `CapDrop` ALL and nothing added, `no-new-privileges`, user, socket mounts), in CI after both stacks start.

## Consequences
- Inspect, which docker_stats needs, shows a container's environment: Keycloak's admin and client secrets reach it
  through `env_file`, so the collector could read them. Keycloak cannot read those values from files; a deployment
  injects them from its secret store (Phase 8).
- The service still holds the owner's password, for Flyway at its start. Running migrations as a separate job, so the
  running service never sees it, belongs with the deployment (Phase 8).
- A secret per role: `generate_secrets.py` makes the three runtime passwords and the monitoring one; an existing
  `secrets/` gets them on its next run, and the next start sets them.
