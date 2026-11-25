# 40. TLS on every hop, mutual TLS between the services

Date: 2026-11-25 · Status: Accepted · Builds on ADR 0039 (internal CA), ADR 0017 (tokens), ADR 0015 (edge)

## Context
ADR 0039 gives every container a certificate from an internal CA, renewed every 16 h. This decides what each
connection does with it. The Phase 7 brief asks for HTTPS at the gateway with HSTS and Mozilla's cipher list; mutual
TLS from the gateway to the services and from logistics to banking, on top of the JWT, each service accepting only the
callers it expects; verified TLS from the services to Postgres, Kafka, Redis and Keycloak; rotation without downtime.

## Sources, checked 2026-11-25
- Mozilla's server-side TLS guidelines **6.0** (ssl-config.mozilla.org/guidelines/6.0.json), intermediate profile:
  TLS 1.2 and 1.3; TLS_AES_128_GCM_SHA256, TLS_AES_256_GCM_SHA384, TLS_CHACHA20_POLY1305_SHA256; for 1.2 only ECDHE
  with AES-GCM or ChaCha20-Poly1305; HSTS max-age 63072000.
- Spring Boot 4.2 SSL bundles: PEM key and trust stores, `reload-on-update` for the embedded Tomcat and Netty servers,
  `SslBundles.addBundleUpdateHandler` for anything else.
- Kafka: dynamic `listener.name.<l>.ssl.keystore.location` (KIP-226), PEM key stores with PKCS#8 keys (KIP-651).
- Redis 8.10: `CONFIG SET tls-cert-file tls-key-file` reloads the TLS context. Postgres 17: certificate and key are
  re-read on a configuration reload. Keycloak 26.8: `https-certificates-reload-period`.

## Decision
**Every TCP connection between containers, and every published port, takes TLS 1.2 or 1.3 with a certificate from
the internal CA, verified by name. The services take only mutual TLS, and only from the identities they list.**

| Hop | Protocol | Who proves what | Renewal | Evidence |
| --- | --- | --- | --- | --- |
| Clients to the gateway (8080, 8085) | HTTPS, Mozilla intermediate 6.0, HSTS 2 years | server certificate | Boot reloads the bundle | `GatewayTlsTest` |
| Gateway to banking, logistics, notification | mutual TLS | both; the service checks the caller's name | `InternalTls` | `GatewayMutualTlsTest`, each service's `CallerCertificateIntegrationTest` |
| logistics to banking | mutual TLS | both | `InternalTls` | `CallerCertificateIntegrationTest` (banking), `BankingFaultInjectionTest` |
| Prometheus to the services | mutual TLS | both | Prometheus re-reads its files | the trace job's scrape |
| Services to Postgres | TLS, `sslmode=verify-full`, SCRAM | server certificate, then password | `pg_ctl reload` | `hostssl` only in `pg_hba.conf` |
| Services, kafka-init, collector to Kafka | SASL_SSL, SCRAM-SHA-512 | server certificate, then password | dynamic listener update | `KafkaCertificateRotationIntegrationTest` |
| Gateway to Redis | TLS only (`--port 0`) | server certificate | `CONFIG SET` | `RateLimitTest` against `infra/redis/start.sh` |
| Services, gateway, scripts to Keycloak | HTTPS only | server certificate | hourly re-read | the smoke test's logins |
| Carrier simulator to the gateway | HTTPS | server certificate | gateway bundle | the smoke test's DCSA push |
| notification, Alertmanager to Mailpit (SMTP) | STARTTLS required, then the SMTP login | server certificate | Mailpit restarted on renewal, its messages in a database file | notification's email tests (Mailpit requires STARTTLS there too) |
| Scripts to Mailpit's API (8025) | HTTPS | server certificate | as above | the smoke test's email checks |
| banking, logistics to the partner simulator | HTTPS; the real ECB, Bank of Namibia and carriers are checked against the JVM's public roots, the simulator against the internal root only where `REFERENCE_RATES_SSL_BUNDLE` and `DCSA_SSL_BUNDLE` say `internal` | server certificate | re-read into the live context | the smoke test's DCSA voyage, CI's rates |

- **Allowed callers by name.** The connector (`server.ssl.client-auth: need`) refuses any client without a certificate
  from the internal CA in the handshake. `CallerCertificateFilter`, ahead of Spring Security, then takes the request only
  if the certificate's common name, the agent's identity, is in `freightflow.tls.allowed-callers`, and otherwise answers
  `403 caller-not-allowed`. banking takes the gateway, logistics, Prometheus and itself; logistics and notification the
  gateway, Prometheus and themselves. The bearer token is checked afterwards exactly as before: TLS says which
  workload is calling, the token which user or client, and a request needs both.
- **Clients follow renewals.** Tomcat, Netty, Kafka, Redis, Postgres and Keycloak reload their server certificates
  (ADR 0039). A client builds its `SSLContext` once, so it would present an expired certificate a day later.
  `InternalTls` (in each service and the gateway) asks the bundle's current key and trust managers on every handshake,
  and Boot swaps them when the agent renews the files; the JDK client to banking, the warm-up's own calls and the
  gateway's Reactor Netty client use it.
- **No plaintext on the network.** What stays plaintext is inside one container: Kafka's controller listener on the
  container's loopback, Redis's Unix socket for its healthcheck and reload, Postgres's Unix socket for its
  initialisation and `pg_isready`. A plaintext request to a TLS port is refused before the application sees it:
  Tomcat answers 400, Netty, Kafka, Redis and Postgres close the connection.
- **Healthchecks** use curl with the container's own files (the images gain curl: BusyBox wget checks no
  certificate); Keycloak's image has no curl, so it gets a small Java client (`infra/keycloak/HealthCheck.java`).
- **The host** trusts the root through `scripts/internal_ca.py`, which copies it out of the running stack. Windows'
  curl is built on Schannel, which ignores `CURL_CA_BUNDLE` and asks for revocation data the CA does not publish, so
  the scripts give curl a `.curlrc` with `cacert` and `ssl-no-revoke`. Host scripts that read a service's actuator do
  it from inside the container, with the certificate the service accepts as itself.

## Options not taken
- **A service mesh (Istio, Linkerd).** The same mutual TLS without code, but a control plane, a sidecar per
  container and a Kubernetes cluster: Phase 8's deployment decision, not a compose one.
- **One CA per caller, or SPIFFE IDs in a URI SAN.** A per-caller trust store would make the allowlist the trust store
  itself, but a renewal would then touch every service. SPIFFE IDs are the standard name for a workload; the agent's
  common name carries the same information here, and the filter would only change the field it reads.
- **mTLS on Postgres, Kafka and Redis instead of passwords.** It would remove the passwords, but each server maps
  certificates to users differently; the passwords and ACLs of ADRs 0019 and 0022 stay, now inside TLS.

## Consequences
- A request from outside reaches a service only through the gateway; a container on the network without a
  certificate from the CA reaches nothing that holds data. A container whose key leaks can still call what its
  identity is allowed to, until its certificate expires (24 h at most).
- Internal suites are the JDK's defaults for TLS 1.3 and 1.2; only the public listener is held to Mozilla's list.
- Still in plaintext when this was accepted, and moved in the commits that follow it: the services' OTLP export to the
  collector and the collector to Tempo and Loki, and Prometheus, Grafana and Alertmanager among themselves.
- The internal CA is never trusted for a public host: the clients of the ECB, the Bank of Namibia and the carriers use
  the JVM's roots unless a setting names the internal bundle, which only a stack that runs the simulator sets.
- Mailpit cannot reload a certificate, so its wrapper restarts it on each renewal (every 16 h); a sender connecting in
  that second is refused and retries. A real relay is a provider's, with a public certificate.
