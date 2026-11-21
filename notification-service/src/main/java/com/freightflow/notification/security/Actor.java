package com.freightflow.notification.security;

import java.util.UUID;

/**
 * Who made a change, recorded on the row it created (ADR 0020).
 *
 * @param subject        the token's subject
 * @param clientId       the OAuth client
 * @param organizationId the caller's organisation, if it has one
 * @param requestId      X-Request-Id of the request (ADR 0015)
 */
public record Actor(String subject, String clientId, UUID organizationId, String requestId) {
}
