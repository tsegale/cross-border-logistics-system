# 19. Service-to-service credentials and secrets

Date: 2026-11-11 · Status: Accepted

## Context
Since Phase 2 logistics-service authorises, captures, extends and releases holds in banking-service. The holds API
was protected by "the gateway does not route it": any container on the compose network could reserve or capture any
customer's money. Secrets (database passwords) were defaults in `application.yml` and values in `.env`.

## Decision
- **logistics-service has its own identity**: a confidential Keycloak client `logistics-service`, client-credentials
  grant only, whose tokens carry `aud = banking-service` and the scope `banking.holds`, no roles. banking's
  `/api/v1/holds/**` requires exactly that scope; a person's token, whatever its roles, is a 403.
- **Spring Security's OAuth2 client** fetches and caches the token (`AuthorizedClientServiceOAuth2AuthorizedClientManager`,
  usable from the relay's own threads, outside any HTTP request) and `OAuth2ClientHttpRequestInterceptor` adds it to
  every call of the banking HTTP service group. The token is renewed 30 s before it expires (`token.refresh-skew`).
- **The token request is part of the banking call**, so ADR 0013's breaker, bulkhead and in-call retries cover it,
  with its own 2 s connect and read timeouts. If no token can be obtained (Keycloak down), the call never reaches
  banking: the command is not charged an attempt, stays `PENDING`, and is retried after the backoff; nothing is lost
  and, because the idempotency key is unchanged, nothing is duplicated (`BankingFaultInjectionTest` pauses Keycloak
  while every attempt needs a fresh token).
- **Delegation is explicit.** logistics acts for a customer, so every hold says for whom: `payerOrganizationId`
  (banking refuses, as 404, a payer account that organisation does not own) and `onBehalfOfSubject` (recorded on the
  hold, ADR 0020). Banking trusts these fields only from a token with the `banking.holds` scope.
- **No secret in git, compose, `application.yml` or an image.** `scripts/generate_secrets.py` writes every value into
  the git-ignored `secrets/`. Compose mounts single-value files as compose secrets: PostgreSQL reads
  `POSTGRES_PASSWORD_FILE`; the services read `/run/secrets/*` through Spring's config tree
  (`spring.config.import: optional:configtree:/run/secrets/`), so `banking_db_password` becomes a property. Keycloak
  cannot read secrets from files, so its values (admin, database, realm placeholders) come from `secrets/keycloak.env`,
  loaded only by its container. `application.yml` has no default for any of them, and `RequiredSecrets` stops a
  service at startup with one clear message when one is missing (`RequiredSecretsTest`, which also loads the packaged
  `application.yml` and asserts no default survives). CI runs gitleaks on the whole history.
- **Only the gateway publishes an application port** (8080, and its management port 8085), on 127.0.0.1. Keycloak
  publishes 8180 because people log in through it. Services and databases publish nothing; `--profile debug` adds a
  socat forwarder that publishes 8081, 8082, 5441 and 5442 on 127.0.0.1 for psql and debuggers, and the benchmark
  overlay publishes banking's 8081 for its harness. CI checks that the default stack publishes none of them.

## Consequences
- Rotating logistics' client secret means updating Keycloak and the secret file together; the realm file's
  placeholder only applies on first import.
- Keycloak is now on the payment path: a Keycloak outage longer than a cached token's life (5 minutes) delays
  payments, never loses them.
- **TLS in production** (Phase 7): TLS terminates at an ingress or load balancer in front of the gateway and of
  Keycloak's public URL; service-to-service and database traffic uses mTLS from the platform (a service mesh or the
  cloud provider's private networking) rather than certificates managed in each Spring service. Plain HTTP stays a
  local-compose property.
