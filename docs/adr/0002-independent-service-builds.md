# 2. Independent service builds, no shared library

Date: 2026-11-07 · Status: Accepted

## Context
The first scaffold was a Maven multi-module build: one parent POM for every service and a `common-lib` module
for shared DTOs, event classes and exceptions. That is convenient on day one and expensive later:

- Changing a shared DTO or event class forces every service to rebuild and redeploy together, which is a
  distributed monolith with extra network hops.
- One parent POM means one Spring Boot version for every service. A service can't take a framework upgrade
  on its own.
- Shared event classes hide the real contract. A consumer compiled against the producer's class breaks at
  runtime when the producer's JSON changes, and nothing tests that.

## Options
1. **Parent POM and shared library** (the scaffold).
2. **Parent POM for build settings only**, no shared code.
3. **Fully independent services**, each with its own Spring Boot parent and Maven wrapper; a root POM that
   only aggregates the modules for a one-command build.

## Decision
Option 3. Each service is a standalone Maven project with its own `mvnw`, its own version pins and its own
Dockerfile build context. The root `pom.xml` only lists the modules.

Contracts replace shared code:
- REST: each service publishes its OpenAPI document; clients own their request and response types.
- Events: JSON Schemas in `docs/events/`, with a contract test in the producer (what it publishes validates)
  and one in each consumer (the schema's examples deserialise).

## Consequences
- Some duplication: a consumer declares its own small record for an event it reads. That is deliberate: it
  only declares the fields it uses, so a producer can add fields freely.
- Version drift between services is possible. Dependabot updates every service weekly in one grouped PR.
- Build settings that should be the same everywhere (Java version, coverage gate) are kept consistent by
  review, not by inheritance.
