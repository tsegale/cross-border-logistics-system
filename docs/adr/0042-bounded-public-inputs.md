# 42. Bounded public inputs

Date: 2026-11-26 · Status: Accepted · Builds on ADR 0015 (edge limits), ADR 0017 (rate limits), ADR 0037 (carrier
webhooks)

## Context
The gateway limited bodies to 1 MB and money-moving routes per caller (ADR 0017). Everything else a client controls
was bounded by library defaults or not at all:
- **Carrier webhooks.** They arrive without a token, so the caller-keyed limiter could not budget them, and they took
  the same 1 MB as everything else.
- **JSON.** Jackson 3's defaults allow 500 levels of nesting, numbers of 1,000 digits and 20 MB strings.
- **Slow clients.** A client sending a byte at a time held a connection, and its buffers, indefinitely.
- **Headers.** The gateway set HSTS only: no CSP, no Referrer-Policy, and no stated CORS position.
- **Error details.** Two services passed the message of any `IllegalArgumentException` to the caller, a library's
  included.
- **Scanning.** No scanner had looked at the edge.

The Phase 7 brief asks for all of this, a ZAP API scan with a reviewed baseline, and an OWASP API Security Top 10
(2023) mapping.

## Sources, checked 2026-11-26
- OWASP API Security Top 10 2023 (owasp.org/API-Security/editions/2023), and the OWASP REST Security Cheat Sheet
  (security headers for APIs: `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`, `nosniff`,
  `Referrer-Policy`, no CORS unless needed).
- Jackson 3.1 (`tools.jackson.core.StreamReadConstraints`): nesting depth, document, string, name and number lengths,
  token count; a violation is a `StreamConstraintsException` while parsing.
- reactor-netty 1.3 `HttpServer`: `idleTimeout` (until a request arrives), `readTimeout` and `requestTimeout` (while
  its body is read), shown by `EdgeHardeningTest` to apply in that order; Boot 4.2 exposes the first as
  `server.netty.idle-timeout` and neither of the others.
- Spring Security 7 `ServerHttpSecurity.headers()`: writes only through the security chain, which a filter that
  answers first skips. Hence a filter of the gateway's own.
- ZAP 2.17.0 (github.com/zaproxy/zaproxy/releases, 2025-12-15; image `zaproxy/zap-stable:2.17.0`, pinned by digest):
  `zap-api-scan.py`, its rule configuration file and `ZAP_AUTH_HEADER_VALUE`.

## Decision
- **Webhooks have budgets of their own.** `RateLimit` takes a `key`: the caller (as before), the carrier in the path,
  or the source address. Each key has its own rate, and a request must fit every budget on its route. The webhook
  route has two budgets, 10/s with a burst of 50 per carrier and 5/s with a burst of 20 per address, and fails closed
  without Redis: a carrier retries, and logistics also polls. Refusals are counted from zero
  (`freightflow_gateway_rate_limited_total{route,key}`), and `CarrierWebhooksRateLimited` opens a ticket.
- **Webhook bodies stop at 256 KB**, at the gateway (413 before the body is read) and in logistics, before the
  signature is computed over it.
- **JSON is bounded while it is parsed**, in every service's mapper and in logistics' webhook parser:
  - nesting depth 32;
  - a megabyte per document;
  - strings to 100,000 characters, names to 256;
  - numbers to 64 digits (a `NUMERIC(19,4)` amount needs 24);
  - 100,000 tokens.

  A violation is the generic `invalid-request-body` problem, which names no parser limit.
- **Slow and bloated requests are cut off at the edge**:
  - headers to 16 KB;
  - 20 s without a byte until the headers are in;
  - then 10 s of silence, or 30 s in all, for the body.

  The services' Tomcat takes the same header limit, a 10 s connection timeout and a 20 s keep-alive.
- **Security headers for a JSON API**, from one filter that runs before everything else (`SecurityHeadersFilter`),
  so a response a filter writes before the security chain carries them too. ZAP's first scan found HSTS missing on
  exactly such a response, the internal paths' 404.
  - `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`;
  - `Referrer-Policy: no-referrer`, `Cross-Origin-Resource-Policy: same-origin`;
  - `nosniff`, `X-Frame-Options: DENY`, `Cache-Control: no-store`;
  - HSTS (ADR 0039).

  Each is written only where the response has none, so Keycloak's login pages keep their own policy.
- **CORS is closed**: no configuration source, so no origin is allowed and a preflight gets no `Access-Control-*`
  field. The first browser front end adds its one origin here.
- **A problem's detail is written for the caller, or it is generic.** Messages meant for the caller are an
  `InvalidRequestException`. Any other illegal argument, unexpected exception or constraint violation is answered
  with a fixed detail and logged.
- **ZAP's API scan runs on every CI build.** It runs against the gateway inside the compose network, with a
  customer's token, over the OpenAPI documents banking and logistics publish. The active scan is capped at
  4 minutes per API, inside the token's 5-minute lifetime. `infra/zap/rules.tsv` is the reviewed baseline: a rule
  marked FAIL that fires fails the build, and each IGNORE names its reason. The full rule list is written to the
  artifact each run, so a rule that a new ZAP release adds is reported before it is judged.

## Consequences
- **A flood of carrier notifications costs Redis lookups, not database work.** One address cannot spend a carrier's
  budget beyond its own.
- **The source budget is per address as the gateway sees it.** Behind a load balancer that is the balancer's address,
  until the gateway trusts its `Forwarded` header (production, Phase 8).
- **Tuned limits fail loudly.** A real request over a JSON limit, which no client sends today, is a 400 and a log
  line, so the limit is raised deliberately.
- **The headers fit APIs only.** A browser front end served from this origin needs its own CSP, written for its pages.
- **ZAP scans as a customer only.** Operator routes are covered by the authorisation tests (ADR 0018), not by the
  scanner. It runs inside the default-stack job, so its time adds to that job's.
