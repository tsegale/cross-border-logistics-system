# Service down

Alerts: ServiceDown, GatewayUpstreamErrors

**What it means.** A service or Keycloak does not answer its metrics scrape for a minute, or more than 5 % of the
gateway's requests end in a server error.

**Check.**
1. `docker compose ps`: restarting, exited, unhealthy? `docker compose logs --tail 200 <service>`.
2. Out of memory: the container's memory against its limit on [Runtime](http://127.0.0.1:3000/d/runtime);
   `docker inspect -f '{{.State.OOMKilled}}' <container>`.
3. Its database, Kafka or Keycloak, which its readiness checks (ADR 0015).

**Fix.** Restart it once the cause is clear; outboxes and consumers resume where they stopped. An OOM kill means its
limit or a leak: compare with ADR 0029's measured peaks. **Escalate** to the service's owner.
