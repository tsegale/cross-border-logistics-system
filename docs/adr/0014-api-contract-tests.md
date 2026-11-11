# 14. API contract tests: a committed OpenAPI document on both sides

Date: 2026-11-11 · Status: Accepted

## Context
logistics-service now calls banking-service for FX rates and holds. ADR 0002 rules out a shared client library:
each side owns its types and the contract is banking's OpenAPI document. Without a test on each side, either can
drift silently: banking renames a field and logistics deserialises `null`; logistics' stubs answer something banking
never would, and its tests pass against a fiction.

## Options
1. **Spring Cloud Contract.** Producer-side Groovy or YAML contracts generate producer tests and WireMock stubs for
   the consumer. Version 5.0.x targets Spring Boot 4, but it adds a second contract language next to OpenAPI, a
   stub jar that has to be published and fetched across two independently built services (ADR 0002), and its
   value is highest when the producer team does not already publish a spec.
2. **Pact.** Consumer-driven, needs a broker (or files passed between builds) and a verification step in the
   producer's build. Strong for many consumers; with one consumer and one producer in one repository it is
   ceremony.
3. **The OpenAPI document as the contract, checked on both sides.** banking commits its served document; a banking
   test fails if the served document drifts from the committed one. logistics validates every stub it uses against
   that committed document.

## Decision
Option 3. It keeps one contract format, already the one ADR 0002 names, and needs no broker or stub publication.

- **Producer (banking).** `docs/api/banking-openapi.json` is the committed contract. `OpenApiContractTest` fetches
  `/docs/banking/v3/api-docs` from the running application, normalises it (sorted keys, server URL removed) and
  compares it with the file. A deliberate API change regenerates the file
  (`./mvnw test -Dtest=OpenApiContractTest -Dcontract.update=true`) and the diff is reviewed in the pull request.
- **Consumer (logistics).** `BankingStubs` holds every request logistics sends and every response it expects from
  banking. `BankingContractTest` loads the committed document from `../docs/api/` and validates each one with the
  Atlassian `swagger-request-validator-core` (`OpenApiInteractionValidator`): method, path, required headers
  (`Idempotency-Key`), request body schema, status code and response body schema. The WireMock stubs used by the
  saga and fault-injection tests are built from the same `BankingStubs`, so a stub that the contract does not allow
  fails the build.
- A breaking change in banking fails banking's own build first (the committed document differs); once the file is
  regenerated, logistics' contract test fails until its stubs and types follow.

## Consequences
- Both builds stay independent (ADR 0002): logistics reads a file from the repository, not an artifact.
- The contract covers shapes and status codes, not behaviour; behaviour across both services is covered by the
  fault-injection tests that run real banking-service in a container (ADR 0013).
- springdoc output must be deterministic; the test normalises ordering so only real changes show up.
