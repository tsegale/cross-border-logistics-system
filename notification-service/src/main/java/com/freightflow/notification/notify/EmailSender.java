package com.freightflow.notification.notify;

import com.freightflow.notification.config.NotificationProperties;
import com.freightflow.notification.notify.NotificationStore.Notification;
import com.freightflow.notification.notify.NotificationStore.Status;
import com.freightflow.notification.telemetry.Redaction;
import com.freightflow.notification.telemetry.TraceContext;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends queued emails over SMTP (ADR 0027), at most once each ({@link NotificationStore}). Every email carries a
 * {@code Message-ID} derived from its row, so a mail client that sees it twice could tell. Safe on several
 * instances: rows are claimed with {@code FOR UPDATE SKIP LOCKED}.
 */
@Component
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final NotificationStore store;
    private final JavaMailSender mail;
    private final NotificationProperties properties;
    private final MeterRegistry meters;
    private final Clock clock;
    private final boolean enabled;
    private final TraceContext traces;

    public EmailSender(NotificationStore store, JavaMailSender mail, NotificationProperties properties,
                       MeterRegistry meters, Clock clock, TraceContext traces,
                       @org.springframework.beans.factory.annotation.Value("${freightflow.notifications.sender-enabled:true}")
                       boolean enabled) {
        this.store = store;
        this.traces = traces;
        this.mail = mail;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
        this.enabled = enabled;
        for (Status status : List.of(Status.PENDING, Status.FAILED, Status.UNKNOWN)) {
            Gauge.builder("freightflow.emails", store, s -> s.count(status))
                    .description("Emails by status: PENDING waits, FAILED was refused every time, UNKNOWN may not "
                            + "have arrived")
                    .tag("status", status.name())
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${freightflow.notifications.send-interval:500ms}")
    public void scheduledSend() {
        if (enabled) {
            sendDue();
        }
    }

    /** One run: abandons dead senders' emails, then sends what is due. Returns how many went out. */
    public int sendDue() {
        int abandoned = store.abandonExpiredLeases();
        if (abandoned > 0) {
            meters.counter("freightflow.emails.unknown").increment(abandoned);
            log.error("{} email(s) were being sent when their sender stopped; marked UNKNOWN, not sent again",
                    abandoned);
        }
        int sent = 0;
        for (Notification notification : store.claim(properties.sendBatch(), properties.lease())) {
            if (send(notification)) {
                sent++;
            }
        }
        return sent;
    }

    /**
     * Sends one email in the trace of the event that queued it (ADR 0030). The span carries the notification, its
     * kind and event, never the recipient or the body (ADR 0024).
     */
    private boolean send(Notification notification) {
        Span span = traces.continueFrom(notification.traceparent(), "email send")
                .tag("freightflow.notification.id", notification.id().toString())
                .tag("freightflow.notification.kind", notification.kind().code())
                .tag("freightflow.event.type", notification.eventType())
                .tag("freightflow.event.id", notification.eventId().toString())
                .tag("freightflow.email.attempt", Integer.toString(notification.attempts()));
        if (notification.shipmentId() != null) {
            span.tag("freightflow.shipment.id", notification.shipmentId().toString());
        }
        try (var scope = traces.inScope(span)) {
            try {
                mail.send(message(notification));
            } catch (MailException | MessagingException ex) {
                // A refusal quotes the recipient: never into a log line or a span (ADR 0024).
                String error = Redaction.emails(ex.toString());
                boolean giveUp = notification.attempts() >= properties.maxAttempts();
                Instant retryAt = clock.instant().plus(backoff(notification.attempts()));
                store.refused(notification.id(), ex.toString(), retryAt, giveUp);
                meters.counter("freightflow.emails.failed", "kind", notification.kind().code(),
                        "final", Boolean.toString(giveUp)).increment();
                span.tag("freightflow.email.outcome", giveUp ? "failed" : "refused");
                span.error(new MailRefusedException(error));
                if (giveUp) {
                    log.error("Email {} ({}) failed {} times, giving up: {}", notification.id(),
                            notification.kind().code(), notification.attempts(), error);
                } else {
                    log.warn("Email {} ({}) refused, attempt {}, retrying at {}: {}", notification.id(),
                            notification.kind().code(), notification.attempts(), retryAt, error);
                }
                return false;
            }
            store.sent(notification.id());
            span.tag("freightflow.email.outcome", "sent");
            meters.counter("freightflow.emails.sent", "kind", notification.kind().code(),
                    "language", notification.language()).increment();
            meters.timer("freightflow.emails.delay", "kind", notification.kind().code())
                    .record(Duration.between(notification.createdAt(), clock.instant()));
            // From the change that caused the email to the SMTP server's acceptance (docs/slo.md): the event id is a
            // UUIDv7, whose first 48 bits are the time the producer wrote it, in its transaction (ADR 0023).
            Instant now = clock.instant();
            writtenAt(notification.eventId())
                    // A producer's clock may run a little ahead; anything outside a plausible range is not timed.
                    .filter(written -> written.isAfter(now.minus(Duration.ofDays(30))) && written.isBefore(now.plusSeconds(60)))
                    .ifPresent(written -> meters.timer("freightflow.emails.end_to_end", "kind", notification.kind().code())
                            .record(Duration.between(written, now).isNegative() ? Duration.ZERO : Duration.between(written, now)));
            return true;
        } finally {
            span.end();
        }
    }

    /**
     * The millisecond a UUIDv7 was made (RFC 9562, section 5.7); empty for any other version, whose first bits are no
     * time. The producers' event ids are v7 (ADR 0023).
     */
    static java.util.Optional<Instant> writtenAt(java.util.UUID eventId) {
        return eventId.version() == 7
                ? java.util.Optional.of(Instant.ofEpochMilli(eventId.getMostSignificantBits() >>> 16))
                : java.util.Optional.empty();
    }

    /** The server's refusal, addresses taken out, as the span's error. */
    static final class MailRefusedException extends RuntimeException {
        MailRefusedException(String message) {
            super(message, null, false, false);
        }
    }

    private MimeMessage message(Notification notification) throws MessagingException {
        MimeMessage message = mail.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(properties.from());
        helper.setTo(notification.recipient());
        helper.setSubject(notification.subject());
        helper.setText(notification.bodyText(), notification.bodyHtml());
        message.setHeader("Content-Language", notification.language());
        message.setHeader("X-FreightFlow-Event-Id", notification.eventId().toString());
        // JavaMailSenderImpl keeps a Message-ID that is set before sending.
        message.setHeader("Message-ID", "<" + notification.id() + "@notifications.freightflow.dev>");
        return message;
    }

    /** Exponential with jitter: base x 2^(attempt-1), capped, then a random point in [cap/2, cap]. */
    private Duration backoff(int attempt) {
        long cap = Math.min(properties.maxRetryBackoff().toMillis(),
                properties.retryBackoff().toMillis() << Math.min(Math.max(attempt - 1, 0), 20));
        return Duration.ofMillis(cap / 2 + ThreadLocalRandom.current().nextLong(cap / 2 + 1));
    }
}
