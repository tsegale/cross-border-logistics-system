# 17. Authentication at the gateway and in every service: token relay, not trusted headers

Date: 2026-11-11 · Status: Accepted

## Context
Smart Tours (its ADR 0004) has the gateway verify the token and pass identity on as `X-User-*` headers that the
services trust. That is safe only while nothing but the gateway can reach a service. FreightFlow moves money: one
misrouted port, one compromised container on the compose network, or one future route that skips the gateway, and
anyone who can send `X-User-Id: <someone>` spends someone else's balance. Defence in depth means a service must not
take anyone's word for who is calling.

## Options
1. **Trusted headers** (Smart Tours): the gateway validates, services read headers. Simplest; the network is the
   only control between a forged header and the ledger.
2. **Token relay**: the gateway validates and forwards the original bearer token unchanged; every service validates
   it again against Keycloak's keys, for its own audience.
3. **Token exchange** (RFC 8693): the gateway swaps the user's token for a downstream one per service. Tightest
   audiences, but a Keycloak round trip per request, and Keycloak's token exchange for internal tokens is a separate
   feature to enable and secure.

## Decision
Option 2.

- **The gateway** (Spring Cloud Gateway, WebFlux resource server) validates every request except the public tracking
  view and health: RS256 only, issuer, audience `api-gateway`, `exp`/`nbf` with 30 s of skew. Keys come from
  Keycloak's JWKS on the internal network, cached by `CachingJwkSource`: a cached key id is checked with no request to
  Keycloak; an unknown key id refetches at most once per 30 s (`jwks-min-refresh`), so forged key ids cannot flood
  Keycloak; keys older than 10 minutes refresh on the next request and fall back to the cached set if Keycloak is
  down. A token whose key is not cached while Keycloak is unreachable is a retryable `503 issuer-unavailable`, not a
  401 that would tell a client to throw away a valid session (`IssuerOutageTest`).
- **Coarse role rules at the edge** stop what no role may do: deposits, reversals, carrier assignment and payment
  retries need `operator`; reconciliation and payment commands `operator` or `auditor`; carriers may only post
  tracking events and read shipments; auditors change nothing. A refusal is `403 forbidden`; no token is
  `401 unauthenticated` with `WWW-Authenticate: Bearer` (`error="invalid_token"` when one was rejected; no error
  description, which would help a forger).
- **Forgeable headers are stripped** before anything else sees the request: `X-User-*`, `X-Org-*`,
  `X-Organization-*`, `X-Client-*`, `X-Actor-*`, `X-Roles`, `X-Auth-*`, `X-Authenticated-*`, `X-Forwarded-User`,
  `X-Forwarded-Email`, `Remote-User`. `Authorization` is relayed unchanged, with the request id.
- **Every service is a resource server too** (servlet), for its own audience (`banking-service`,
  `logistics-service`), RS256, issuer, lifetime. Identity reaches a controller only as a `Caller` built from the
  validated token (subject, client id, organisation, roles, scopes); no service reads identity from a header or a
  body. A request sent straight to a service without a valid token is a 401 (every endpoint in both services'
  authorisation matrix tests runs anonymously too). Keycloak unreachable is a 503 there as well (`IssuerOutageFilter`,
  in front of Spring Security's chain, which otherwise rethrows the failure as a 500).
- **Rate limiting** per caller (`caller:<azp>:<sub>`) on money-moving routes (POST transfers, deposits, FX quotes,
  bookings), Redis token buckets as in Smart Tours' ADR 0010. Money-moving routes fail **closed**
  (`503 rate-limiter-unavailable`) when Redis is down; nothing else is rate limited. A refusal is `429 rate-limited`
  with `Retry-After` and the IETF `RateLimit-Policy` / `RateLimit` fields of draft-ietf-httpapi-ratelimit-headers-11:
  `"<route>";q=<burst>;w=<seconds to refill>` and `"<route>";r=<tokens left>;t=<seconds until full>`.
- **Security events** go to the `freightflow.security` logger as structured ECS fields: action
  (`authentication-failed`, `access-denied`, `rate-limited`, `issuer-unavailable`), request id, client id, subject,
  method, path, status. Never the token.

## Consequences
- Each service validates each request: one signature check per hop, no network call while keys are cached.
- Every token a person gets carries all three audiences, because the gateway relays it to whichever service the
  route names. A carrier's token leaves `banking-service` out, so it cannot reach banking even directly.
- The services and the gateway keep separate copies of the same few security classes; ADR 0002 forbids a shared
  library, and the copies are small.
- TLS is not here: in compose all traffic is plain HTTP on one host. Phase 7 terminates TLS at the edge and between
  services (docs/threat-model.md).
