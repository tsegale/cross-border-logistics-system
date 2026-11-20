package com.freightflow.logistics.events;

import com.freightflow.logistics.security.Actor;

import java.time.Instant;
import java.util.UUID;

/**
 * Every event logistics publishes, as docs/events/envelope.schema.json describes it (ADR 0023). Serialised as is, in
 * this field order, with nulls written out.
 *
 * @param payload one of the records in {@link LogisticsEvents}
 */
public record EventEnvelope(UUID eventId, String type, int schemaVersion, Instant occurredAt, UUID aggregateId,
                            long sequence, EventActor actor, String requestId, Object payload) {

    /** Who caused the event (ADR 0020). The subject is the token's opaque id, never a name (ADR 0024). */
    public record EventActor(String subject, String clientId, UUID organizationId) {

        static EventActor of(Actor actor) {
            return actor == null ? null : new EventActor(actor.subject(), actor.clientId(), actor.organizationId());
        }
    }
}
