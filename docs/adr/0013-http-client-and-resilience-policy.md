# 13. HTTP client and resilience policy for calls to banking-service

Date: 2026-11-11 · Status: Accepted

## Context
logistics-service calls banking-service for indicative FX rates (on the quote path, a customer waits) and for
holds, captures and releases (on the payment relay, nobody waits; ADR 0012). banking can be slow, down, or answer
and lose the answer. The client must bound how long it waits, retry only what is safe, stop hammering a banking
that is down, and never let a slow banking take every logistics thread or connection.

## Options
**HTTP client.**
1. *Spring Cloud OpenFeign.* The project declares itself feature-complete since Spring Cloud 2022.0 and accepts
   only bug fixes; its own documentation recommends Spring's HTTP service clients for new code.
2. *Spring Framework 7 HTTP interface clients* (`@HttpExchange` on `RestClient`), with Spring Boot 4's
   `@ImportHttpServices` and `spring.http.serviceclient.<group>.*` properties for base URL and timeouts.

**Resilience.**
1. *Spring Framework 7's own resilience*: `@Retryable` / `RetryTemplate` (with an overall `timeout`, jitter and a
   retry predicate) and `@ConcurrencyLimit`. No circuit breaker; `@ConcurrencyLimit` throttles callers by
   blocking them, which is the wrong behaviour for a bulkhead that should shed load.
2. *Resilience4j* (`resilience4j-spring-boot4` 2.4.0, which targets Spring Boot 4): circuit breaker, bulkhead,
   retry, time limiter, configured by properties, with Micrometer metrics.

## Decision
- **HTTP interface clients on `RestClient`** (`BankingApi`), group `banking`, configured by
  `spring.http.serviceclient.banking.{base-url, connect-timeout, read-timeout}`. Logistics owns its request and
  response types (ADR 0002); the contract test checks them (ADR 0014).
- **Both resilience libraries, each for what it does best**: Spring's `RetryTemplate` for retries, because it has a
  total time budget (`timeout`) and a predicate, and Resilience4j for the circuit breaker and the bulkheads, which
  Spring does not offer. Retry is outermost, so each attempt passes the breaker and the bulkhead:
  `retry(budget) -> circuit breaker "banking" -> bulkhead -> HTTP call`.

| Setting | Value | Why |
| --- | --- | --- |
| connect timeout | 500 ms | Same host or network; a connect that takes longer means banking is not there. |
| read timeout | 2 s | A hold is one short transaction (about 9 ms of SQL measured on an idle machine, ADR 0005). 2 s covers banking under heavy load while bounding how long a relay thread waits; a timeout is only an unknown outcome, retried with the same key, never a lost payment. |
| retries | 2 after the first attempt, payment commands only | They are POSTs, safe to repeat only because each carries its idempotency key (ADR 0012). |
| retry on | timeout, connection error (`ResourceAccessException`), `5xx` | Outcome unknown or banking failing. Never a `4xx`: banking answered and repeating will not change the answer. |
| backoff | 100 ms, x2, max 400 ms, plus up to 50 ms jitter | Quick enough to ride out a blip in one relay run; jitter keeps several relay instances from retrying in step. |
| budget | 5 s per call, retries included | Beyond it, the relay's durable retry takes over (ADR 0012: 1 s doubling to 5 min, then parked). |
| circuit breaker | count window 20, at least 10 calls, opens at 50% failures or 50% calls slower than 2 s, open 10 s, 3 trial calls half-open | Ten calls is one or two relay batches. 10 s open gives banking room to recover without leaving payments waiting long. `4xx` and a full bulkhead are not failures. |
| bulkhead `banking-payments` | 8 concurrent, no waiting | The relay sends a batch at a time; 8 bounds the threads and connections a slow banking can hold. |
| bulkhead `banking-fx` | 4 concurrent, no waiting | Quote requests run on Tomcat threads: a slow banking must not take more than 4 of them. A refused rate means the quote goes out without its indicative figure. |

- **What an open breaker or full bulkhead means.** The relay records the command as untried (`attempts` is given
  back) and retries it after the backoff; it never counts towards parking. FX rates are never retried: the quote is
  issued without the indicative figure.
- **Connection pool.** The JDK `HttpClient` behind `RestClient` has no pool cap of its own; the two bulkheads are the
  cap (at most 12 concurrent requests to banking).
- **Metrics.** `freightflow.banking.retries` (in-call retries), Resilience4j's `resilience4j.circuitbreaker.state`,
  `resilience4j.circuitbreaker.calls` and `resilience4j.bulkhead.available.concurrent.calls`, and
  `freightflow.payment.commands{command,outcome}` and the `freightflow.payment.commands.parked` gauge (ADR 0012).
- **Fault-injection tests** (`BankingFaultInjectionTest`) run the real banking-service image behind Toxiproxy
  (Testcontainers 2 `testcontainers-toxiproxy`) with logistics in-process: banking slower than the read timeout,
  banking down, the connection cut after banking committed (the response is lost), banking answering `503` while
  its database is paused, and the breaker opening and closing. Each test ends by asking banking for the shipment's
  holds (exactly one) and the reconciliation (clean).

## Consequences
- Two libraries instead of one, each used for one job; both are configured in `application.yml`.
- The quote path degrades instead of failing when banking is unhealthy; the payment path waits and retries.
- `resilience4j-spring-boot4` 2.4.0 is new; its auto-configuration is used only for registries and metrics, no
  annotations or AOP.
