# 39. An internal CA with short-lived certificates, renewed by one agent

Date: 2026-11-25 · Status: Accepted · Builds on ADR 0019 (secrets), ADR 0029 (memory limits)

## Context
Every connection inside the stack was plaintext: clients to the gateway, the gateway to the services, logistics to
banking, the services to Postgres, Kafka, Redis, Keycloak, the collector and Mailpit (threat model, residual risks).
Encrypting them needs certificates that every component trusts, and a way to replace them before they expire
without restarting anything. It has to run with one `docker compose up -d --wait` on Windows (Docker Desktop) and on
CI's Linux runners, with no host tools beyond Docker and Python: Windows has no openssl.

## Sources, checked 2026-11-25
- smallstep **step-ca v0.30.2** (2026-03-23) and **step CLI v0.31.0** (2026-09-25), the latest GitHub releases; images
  `smallstep/step-ca:0.30.2` and `smallstep/step-cli:0.31.0` (Alpine, both run as their non-root user `step`, uid
  1000).
- The step-ca image initialises a CA on its first start from `DOCKER_STEPCA_INIT_NAME`, `_DNS_NAMES`,
  `_PROVISIONER_NAME` and `_PASSWORD_FILE` (its `/entrypoint.sh`); the password protects the CA's keys and the JWK
  provisioner's.
- step-ca's documentation: "By default, step-ca issues short-lived certificates that expire after 24 hours"; renewal
  (`step ca renew`) authenticates with the current certificate over mTLS; `allowRenewalAfterExpiry` is off by default
  and documented as a risk.
- `step certificate needs-renewal`: a certificate needs renewal once 66 % of its lifetime has passed; `--expires-in`
  takes a remaining duration or percentage instead.

## Options
1. **Certificates generated once by a one-shot container** (Python's `cryptography` or step's `certificate create`),
   valid for months. Simple, but a long-lived key is the thing short-lived certificates exist to avoid, nothing ever
   rotates them, and rotation without downtime would be untested.
2. **A running CA**, issuing short-lived certificates that are renewed automatically. More moving parts, but the way
   internal PKI runs in production (step-ca, Vault PKI, cert-manager), and rotation happens every day, so it is
   exercised all the time instead of once a year.

## Decision
**Option 2: step-ca in compose, and one certificate agent.**

- **The CA** (`ca`) is initialised on its first start, its root and intermediate keys encrypted with `ca_password`
  (a compose secret from `generate_secrets.py`, ADR 0019), in its own volume. It is not published and has no
  remote management. It issues 24 h certificates.
- **The agent** (`cert-agent`, `infra/tls/agent.sh` in the step-cli image) issues a certificate to every identity in
  `infra/tls/identities.conf`: the identity is the common name and the first DNS name, with the container's other
  names. It checks every 5 minutes; a certificate is renewed once two thirds of its lifetime are gone (with 8 h
  left), over mTLS with the current one, and issued afresh with the provisioner when it is missing or already expired
  (a laptop that slept through its renewal). Renewing after expiry is not turned on in the CA.
- **One agent, not a sidecar per container.** A sidecar per consumer is 14 more containers (ADR 0029's memory
  budget) for no difference in what an attacker gains: each sidecar would hold the provisioner password too. The
  agent holds the provisioner password and can mint any certificate; it has no published port, no other secret, and
  no Docker socket.
- **Files.** `/<identity>/cert.pem` (leaf and intermediate), `key.pem` and `ca.pem` (the root) in the `certs` volume.
  The key is 0640, owned by the agent, in the consumer's group (`group_add` gives the agent those groups), so each
  container reads its own key as its own non-root user and no other. Postgres refuses a key that is group-readable
  unless root owns it, so it copies its key to a 0600 file of its own. New files are written next to the old ones and
  renamed into place, key first.
- **Each consumer mounts only its own directory**, read-only (a volume subpath).
- **Root of trust.** Every client trusts the root and verifies the server's name: no `trustAll`, no
  `sslmode=require`. The root lives as long as the CA's volume; replacing it means removing the volume and recreating
  every certificate, which the agent does on its next start.

## Consequences
- Every container gets its first certificate in a few seconds after the CA is healthy; everything that uses TLS
  waits for the agent to be healthy (`depends_on`).
- A certificate is replaced every 16 h. Each consumer reloads it without a restart: Spring Boot SSL bundles
  (`reload-on-update`), Keycloak (`https-certificates-reload-period`), the collector (`reload_interval`), Postgres (a
  configuration reload), Kafka (a dynamic listener update) and Redis (`CONFIG SET`), as the commits that move each of
  them to TLS describe. A metric and an alert watch certificates nearing expiry (`docs/runbooks/certificate-expiry.md`).
- If the CA or the agent is down for more than 8 h, certificates expire and connections fail closed. The alert fires
  well before.
- `docker compose down -v` removes the CA with the rest: the next start makes a new root. Nothing outside the stack
  trusts the root, so nothing outside breaks.
