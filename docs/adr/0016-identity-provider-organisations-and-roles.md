# 16. Identity provider: Keycloak, organisations and roles

Date: 2026-11-11 · Status: Accepted

## Context
Until Phase 3 anyone who could reach the gateway could move money out of any account: no request carried an
identity. FreightFlow's customers are companies (a fish exporter in Walvis Bay, an importer in Hamburg), each with
several people; FreightFlow's own staff run deposits and reconciliation; carriers report tracking events from their
systems; auditors read and change nothing. logistics-service itself calls banking-service and must prove who it is.

## Decision
**Keycloak 26.8.2**, built into a pinned image with the build step done up front (`infra/keycloak/Dockerfile`, starts
with `--optimized`), its own PostgreSQL 17, and the realm as code: `infra/keycloak/realm/freightflow-realm.json`,
imported on first start.

- **Customers are Keycloak Organizations**, a supported feature in Keycloak 26 switched on per realm
  (`organizationsEnabled`). Each customer user is a member of one organisation. The organisation's Keycloak id is
  FreightFlow's organisation id: accounts and shipments are owned by it (ADR 0018). The realm fixes the ids of the
  demo organisations (`namib-exports`, `hanseatic-imports`); checked on a real container, the import keeps them.
- **Realm roles**: `customer`, `operator`, `carrier`, `auditor`. They reach tokens as a top-level `roles` claim from
  one client scope; clients have `fullScopeAllowed: false` and scope mappings name exactly these roles, so
  Keycloak's built-in `default-roles-*`, `offline_access` and `uma_authorization` never appear.
- **Organisation claim.** A custom client scope carries the Organization Membership mapper with the JSON form and
  `addOrganizationId`, so the token says `"organization": {"namib-exports": {"id": "0193a0e0-..."}}`. The built-in
  `organization` scope gives only an array of aliases, and only when the client requests it; an alias can be renamed,
  an id cannot. Measured on Keycloak 26.8.2 before writing the realm.
- **Audiences, one per service.** People's tokens (client scope `freightflow-api`) have
  `aud = [api-gateway, banking-service, logistics-service]`: the gateway relays the token and each service checks
  its own audience (ADR 0017). Carrier tokens (`freightflow-carrier`) leave out `banking-service`. logistics-service's
  own token (`banking.holds`) has only `banking-service` and the scope `banking.holds` (ADR 0019).
- **Clients.** `freightflow-web` is public, authorization code with PKCE (S256 enforced). `logistics-service` and
  `carrier-walvis-haulage` are confidential and use client credentials only; the carrier's service account holds the
  `carrier` role. **No client has the password grant.**
- **Tokens**: access tokens 5 minutes, RS256 (the realm's default signature algorithm), refresh tokens single use.
  Brute-force protection locks a user out temporarily after 5 failures (60 s, growing to 15 minutes); never
  permanently, which would let anyone lock out any user whose name they know.
- **No secret in git, compose or an image.** The realm holds `${PLACEHOLDERS}` resolved from the environment at
  import; `scripts/generate_secrets.py` writes every value into the git-ignored `secrets/`, and only the keycloak
  container loads `secrets/keycloak.env` (Keycloak cannot read a secret from a file; everything else does, ADR 0019).
- **Dev tokens**: `scripts/token.sh <persona>` returns a real token. People log in through Keycloak's own login form
  with the code flow and PKCE, as a browser would; machine clients use client credentials. Personas:
  `customer-namib-exports`, `customer-hanseatic-imports`, `operator`, `auditor`, `carrier-walvis-haulage`,
  `logistics-service`. seed_demo.sh and smoke_test.sh use it.

### Default stack, decided by memory
The brief allowed Keycloak in the default stack only if the measured total still fits the WSL VM (3.7 GiB);
otherwise a dev issuer. Measured with `docker stats` after one login of every persona, on the laptop of the
benchmarks: Keycloak 621 MiB, its database 57 MiB, the whole stack 1,924 MiB idle. After the Phase 2 benchmarks the
stack without Keycloak used 1,810 MiB, so a loaded stack with Keycloak is about 2.5 GiB. It fits: **Keycloak is in
the default stack, CI's stack and smoke test use it, and there is no dev issuer.** Tests that need tokens sign them
with a key of their own and serve it as a JWKS (ADR 0017); the realm itself is tested against a real Keycloak in
Testcontainers.

## Consequences
- **Keycloak reports healthy before it serves.** Its health endpoint was UP while requests still got
  `503 Bootstrap in progress` for 180 s on first start on the loaded laptop: the realm import runs after that point.
  `keycloak_token.py` waits for the realm's discovery document before logging in.
- **Organizations make the login identifier-first**: the username, then a page asking for the password. The token
  script handles both pages.
- **The issuer URL is part of the contract.** `iss` is the public URL (`KC_HOSTNAME`, `http://127.0.0.1:8180`); the
  services fetch keys from `keycloak:8080` on the compose network and must still expect the public `iss`.
- **The realm file applies on first import only.** Keycloak skips a realm that exists, so a change to the file means
  recreating Keycloak's database locally; a live realm in production needs the admin API or a migration tool.
- Keycloak's 621 MiB is under its documented 2 GB for small production deployments; that is sized for a laptop.
