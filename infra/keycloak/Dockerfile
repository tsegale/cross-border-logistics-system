# Keycloak built for this deployment, the way its container guide recommends: the build step runs here, so the server
# starts with --optimized and skips it at every start. https://www.keycloak.org/server/containers
# Organizations are a supported feature in Keycloak 26 and are switched on per realm (organizationsEnabled), not here.
FROM quay.io/keycloak/keycloak:26.8.2 AS builder
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
ENV KC_METRICS_ENABLED=true
RUN ["/opt/keycloak/bin/kc.sh", "build"]

FROM quay.io/keycloak/keycloak:26.8.2
COPY --from=builder /opt/keycloak/ /opt/keycloak/
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
