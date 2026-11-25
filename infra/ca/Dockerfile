# step-ca without the file capability its image gives the binary (cap_net_bind_service, for port 443): the CA listens
# on 9000, needs no capability, and a binary with one cannot start in a container that drops them all and sets
# no-new-privileges (ADR 0041). Nothing else changes.
FROM smallstep/step-ca:0.30.2
USER root
RUN apk add --no-cache --virtual .caps libcap-utils \
 && getcap /usr/local/bin/step-ca \
 && setcap -r /usr/local/bin/step-ca \
 && test -z "$(getcap /usr/local/bin/step-ca)" \
 && apk del .caps
USER step
