package com.freightflow.notification.notify;

import com.freightflow.notification.config.NotificationProperties;
import com.freightflow.notification.notify.NotificationStore.Notification;
import com.freightflow.notification.notify.NotificationStore.Status;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
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

    public EmailSender(NotificationStore store, JavaMailSender mail, NotificationProperties properties,
                       MeterRegistry meters, Clock clock,
                       @org.springframework.beans.factory.annotation.Value("${freightflow.notifications.sender-enabled:true}")
                       boolean enabled) {
        this.store = store;
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

    private boolean send(Notification notification) {
        try {
            mail.send(message(notification));
        } catch (MailException | MessagingException ex) {
            boolean giveUp = notification.attempts() >= properties.maxAttempts();
            Instant retryAt = clock.instant().plus(backoff(notification.attempts()));
            store.refused(notification.id(), ex.toString(), retryAt, giveUp);
            meters.counter("freightflow.emails.failed", "kind", notification.kind().code(),
                    "final", Boolean.toString(giveUp)).increment();
            if (giveUp) {
                log.error("Email {} ({}) failed {} times, giving up: {}", notification.id(),
                        notification.kind().code(), notification.attempts(), ex.toString());
            } else {
                log.warn("Email {} ({}) refused, attempt {}, retrying at {}: {}", notification.id(),
                        notification.kind().code(), notification.attempts(), retryAt, ex.toString());
            }
            return false;
        }
        store.sent(notification.id());
        meters.counter("freightflow.emails.sent", "kind", notification.kind().code(),
                "language", notification.language()).increment();
        meters.timer("freightflow.emails.delay", "kind", notification.kind().code())
                .record(Duration.between(notification.createdAt(), clock.instant()));
        return true;
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
