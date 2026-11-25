# Certificate not renewed

Alerts: CertificateNotRenewed

**What it means.** A certificate from the internal CA (ADR 0039) has less than 6 hours left. The agent renews a 24 h
certificate once 8 hours are left and retries every 5 minutes, so this one has failed to renew for two hours. When
it expires, every TLS connection to or from that container fails closed: a service stops answering the gateway,
a database stops taking connections, the broker stops taking producers (ADR 0040). Nothing falls back to plaintext.

**Check.**
- `docker compose logs cert-agent`: each failure is logged with step's own message (`could not renew <identity>`).
- `docker compose ps ca cert-agent`: the CA must be healthy. The agent is unhealthy when its last full check is more
  than 15 minutes old.
- The time left on every certificate: `min by (tlscheck_x509_cn) (tlscheck_time_left_seconds)` in
  [Prometheus](http://127.0.0.1:9090), or `docker compose exec cert-agent step certificate inspect
  /home/step/<identity>/cert.pem --short`.

**Fix.**
- The CA down or unhealthy: `docker compose up -d --wait ca`. The agent renews at its next check.
- Renewal refused (an expired certificate cannot renew itself over mTLS, and `allowRenewalAfterExpiry` is off): issue
  that one afresh with the provisioner, now: `docker compose exec cert-agent sh /etc/freightflow/tls/agent.sh issue
  <identity>`. Every consumer picks up the new files without a restart (ADR 0039, ADR 0040): within a minute for
  Postgres, Kafka and Redis, within the hour for Keycloak, at once for the JVMs.
- A consumer that still serves the old certificate after its files changed (`step certificate inspect
  https://<host>:<port> --roots /ca/root_ca.crt` from the agent shows the old serial): restart that container.
- The CA's root itself is gone (its volume removed): `docker compose down` and up again recreates the CA and every
  certificate; the hosts' copies of the root (`.tls/ca.pem`) are refreshed by `scripts/internal_ca.py` on its next call.

**Escalate** to whoever owns the deployment's PKI if the CA refuses the provisioner (its password, `ca_password`, or
its keys).

The fire drill triggers this alert by issuing kafka's certificate with a 1 h lifetime and restores it with a 24 h one
(`scripts/alert_fire_drill.py`, phase 6).
