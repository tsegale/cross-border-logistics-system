package com.freightflow.notification.notify;

import com.freightflow.notification.config.NotificationProperties;
import com.freightflow.notification.notify.NotificationExceptions.NotificationNotFoundException;
import com.freightflow.notification.notify.NotificationExceptions.NotificationNotResendableException;
import com.freightflow.notification.notify.NotificationExceptions.ResendNotAcknowledgedException;
import com.freightflow.notification.notify.NotificationStore.Notification;
import com.freightflow.notification.notify.NotificationStore.Status;
import com.freightflow.notification.security.Caller;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * An organisation's email history (ADR 0027): a customer sees its own organisation's, operators and auditors any
 * organisation's or all (ADR 0018). Bodies are not listed; the subject says what each was about.
 *
 * <p>An operator sends an UNKNOWN email again: its sender stopped while handing it to SMTP, so it may or may not have
 * arrived. The request has to acknowledge that the customer may get it twice, and the operator is recorded on the row
 * (ADR 0020). It moves no money, so one operator is enough (ADR 0021).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationStore store;
    private final NotificationProperties properties;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public NotificationController(NotificationStore store, NotificationProperties properties,
                                  TransactionTemplate transactions, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.transactions = transactions;
        this.clock = clock;
    }

    public record NotificationResponse(UUID id, UUID eventId, String eventType, String kind, UUID organizationId,
                                       UUID shipmentId, String recipient, String language, String subject,
                                       String status, int attempts, String lastError, Instant createdAt,
                                       Instant sentAt, int resendCount, Instant resentAt, String resentBy) {

        static NotificationResponse of(Notification n) {
            return new NotificationResponse(n.id(), n.eventId(), n.eventType(), n.kind().code(), n.organizationId(),
                    n.shipmentId(), n.recipient(), n.language(), n.subject(), n.status().name(), n.attempts(),
                    n.lastError(), n.createdAt(), n.sentAt(), n.resendCount(), n.resentAt(), n.resentBySubject());
        }
    }

    /** {@code acknowledgeDuplicate}: the operator knows the customer may receive this email twice. */
    public record ResendRequest(Boolean acknowledgeDuplicate) {
    }

    public record NotificationList(List<NotificationResponse> items) {
    }

    @GetMapping
    public NotificationList list(Caller caller, @RequestParam(required = false) UUID organizationId,
                                 @RequestParam(required = false) String status,
                                 @RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) {
            throw new IllegalArgumentException("limit must be between 1 and 200");
        }
        Status wanted = status == null ? null : parseStatus(status);
        UUID scope;
        if (caller.isCustomer()) {
            // A customer's scope is its own organisation, whatever it asks for (ADR 0018).
            UUID own = caller.requireOrganization();
            if (organizationId != null && !organizationId.equals(own)) {
                return new NotificationList(List.of());
            }
            scope = own;
        } else {
            scope = organizationId;
        }
        return new NotificationList(store.history(scope, wanted, limit).stream().map(NotificationResponse::of)
                .toList());
    }

    @PostMapping("/{id}/resend")
    public NotificationResponse resend(Caller caller, @PathVariable UUID id,
                                       @RequestBody(required = false) ResendRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.acknowledgeDuplicate())) {
            throw new ResendNotAcknowledgedException();
        }
        transactions.executeWithoutResult(tx -> {
            Notification notification = store.lock(id).orElseThrow(() -> new NotificationNotFoundException(id));
            if (notification.status() != Status.UNKNOWN || !store.resend(id, caller.actor())) {
                throw new NotificationNotResendableException(id, notification.status().name());
            }
        });
        log.warn("Notification {} in UNKNOWN sent again by {} ({}): the customer may receive it twice", id,
                caller.subject(), caller.clientId());
        return store.find(id).map(NotificationResponse::of).orElseThrow(() -> new NotificationNotFoundException(id));
    }

    private static Status parseStatus(String status) {
        try {
            return Status.valueOf(status);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("status must be one of " + Arrays.toString(Status.values()));
        }
    }

    /** Recipients are personal data (ADR 0024): the history keeps them for the retention period only. */
    @Scheduled(fixedDelayString = "${freightflow.notifications.prune-interval:6h}", initialDelayString = "5m")
    public int prune() {
        int deleted = store.pruneCreatedBefore(clock.instant().minus(properties.retention()));
        if (deleted > 0) {
            log.info("Pruned {} notifications older than {}", deleted, properties.retention());
        }
        return deleted;
    }
}
