package com.freightflow.notification.notify;

import com.freightflow.notification.config.NotificationProperties;
import com.freightflow.notification.support.Events;
import com.freightflow.notification.support.IntegrationTest;
import com.freightflow.notification.support.Mailpit;
import com.freightflow.notification.support.TestCallers;
import com.freightflow.notification.telemetry.TraceContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sending is at most once and refusals are retried (ADR 0027). These tests drive their own sender against the
 * real table, with an SMTP server that refuses or with a sender that dies mid-send; the context's own sender is off.
 */
@TestPropertySource(properties = "freightflow.notifications.sender-enabled=false")
class EmailSenderIntegrationTest extends IntegrationTest {

    @Autowired
    private NotificationStore store;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JavaMailSender realSmtp;

    @Autowired
    private Clock clock;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private TraceContext traces;

    @Test
    void aRefusingServerIsRetriedWithBackoffThenTheEmailIsMarkedFailed() {
        UUID eventId = queue("refused-" + UUID.randomUUID() + "@example.org");
        EmailSender sender = sender(refusingSmtp(), 3, Duration.ofMinutes(1));

        for (int attempt = 1; attempt <= 3; attempt++) {
            makeDue(eventId);
            sender.sendDue();
        }

        Map<String, Object> row = row(eventId);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("attempts")).isEqualTo(3);
        assertThat((String) row.get("last_error")).contains("connection refused by test");
    }

    @Test
    void aRefusalIsRetriedAndTheEmailGoesOutOnceTheServerAccepts() {
        String recipient = "retry-" + UUID.randomUUID() + "@example.org";
        UUID eventId = queue(recipient);

        sender(refusingSmtp(), 5, Duration.ofMinutes(1)).sendDue();
        assertThat(row(eventId).get("status")).isEqualTo("PENDING");
        makeDue(eventId);
        sender(realSmtp, 5, Duration.ofMinutes(1)).sendDue();

        assertThat(row(eventId).get("status")).isEqualTo("SENT");
        assertThat(row(eventId).get("attempts")).isEqualTo(2);
        assertThat(Mailpit.to(recipient)).hasSize(1);
    }

    @Test
    void anEmailWhoseSenderDiedWhileSendingIsNeverSentAgain() {
        String recipient = "crash-" + UUID.randomUUID() + "@example.org";
        UUID eventId = queue(recipient);
        // A sender claims it (SENDING, leased) and dies before it can say whether SMTP took it.
        jdbcTemplate.update("UPDATE notifications SET status = 'SENDING', attempts = 1, "
                + "next_attempt_at = now() - interval '1 second' WHERE event_id = ?", eventId);

        sender(realSmtp, 5, Duration.ofMinutes(1)).sendDue();

        assertThat(row(eventId).get("status")).isEqualTo("UNKNOWN");
        assertThat(Mailpit.to(recipient)).isEmpty();
    }

    @Test
    void anOperatorSendsAnUnknownEmailAgainOnlyAfterAcknowledgingADuplicateAndIsRecorded() throws Exception {
        String recipient = "resend-" + UUID.randomUUID() + "@example.org";
        UUID eventId = queue(recipient);
        jdbcTemplate.update("UPDATE notifications SET status = 'SENDING', attempts = 1, "
                + "next_attempt_at = now() - interval '1 second' WHERE event_id = ?", eventId);
        EmailSender sender = sender(realSmtp, 5, Duration.ofMinutes(1));
        sender.sendDue();
        UUID id = jdbcTemplate.queryForObject("SELECT id FROM notifications WHERE event_id = ?", UUID.class, eventId);

        mockMvc.perform(get("/api/v1/notifications").param("status", "UNKNOWN").param("limit", "200")
                        .with(TestCallers.as(TestCallers.AUDITOR)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[?(@.id == '%s')].status", id).value("UNKNOWN"));
        mockMvc.perform(get("/api/v1/notifications").param("status", "LOST").with(TestCallers.operator()))
                .andExpect(status().isBadRequest());

        mockMvc.perform(resend(id, null)).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/resend-not-acknowledged"));
        mockMvc.perform(resend(id, "{\"acknowledgeDuplicate\":false}")).andExpect(status().isUnprocessableContent());
        assertThat(row(eventId).get("status")).isEqualTo("UNKNOWN");

        mockMvc.perform(resend(id, "{\"acknowledgeDuplicate\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.resendCount").value(1))
                .andExpect(jsonPath("$.resentBy").value(TestCallers.OPERATOR.subject()));
        Map<String, Object> resent = jdbcTemplate.queryForMap("SELECT resent_by_client_id, resent_at "
                + "FROM notifications WHERE id = ?", id);
        assertThat(resent.get("resent_by_client_id")).isEqualTo(TestCallers.OPERATOR.clientId());
        assertThat(resent.get("resent_at")).isNotNull();

        sender.sendDue();
        assertThat(row(eventId).get("status")).isEqualTo("SENT");
        assertThat(Mailpit.to(recipient)).hasSize(1);

        mockMvc.perform(resend(id, "{\"acknowledgeDuplicate\":true}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://freightflow.dev/problems/notification-not-resendable"))
                .andExpect(jsonPath("$.notificationStatus").value("SENT"));
        mockMvc.perform(resend(UUID.randomUUID(), "{\"acknowledgeDuplicate\":true}"))
                .andExpect(status().isNotFound());
    }

    private MockHttpServletRequestBuilder resend(UUID id, String body) {
        MockHttpServletRequestBuilder request = post("/api/v1/notifications/{id}/resend", id)
                .with(TestCallers.operator());
        return body == null ? request : request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private UUID queue(String recipient) {
        UUID eventId = Events.uuidV7();
        transactions.executeWithoutResult(status -> store.add(eventId, "shipment.booked",
                NotificationKind.SHIPMENT_BOOKED, UUID.randomUUID(), null, recipient, "en",
                new EmailRenderer.Email("Shipment booked: FF-00000000-0", "text", "<p>html</p>")));
        return eventId;
    }

    private Map<String, Object> row(UUID eventId) {
        return jdbcTemplate.queryForMap("SELECT status, attempts, last_error FROM notifications WHERE event_id = ?",
                eventId);
    }

    private void makeDue(UUID eventId) {
        jdbcTemplate.update("UPDATE notifications SET next_attempt_at = now() WHERE event_id = ? "
                + "AND status = 'PENDING'", eventId);
    }

    private EmailSender sender(JavaMailSender smtp, int maxAttempts, Duration lease) {
        NotificationProperties properties = new NotificationProperties(null, Duration.ofMillis(100), 50, lease,
                maxAttempts, Duration.ofMillis(10), Duration.ofMillis(20), Duration.ofDays(90));
        return new EmailSender(store, smtp, properties, new SimpleMeterRegistry(), clock, traces, false);
    }

    /** An SMTP client whose server never accepts: nothing is delivered. */
    private static JavaMailSender refusingSmtp() {
        return new JavaMailSenderImpl() {
            @Override
            public MimeMessage createMimeMessage() {
                return new MimeMessage(Session.getInstance(new Properties()));
            }

            @Override
            protected void doSend(MimeMessage[] messages, Object[] originals) {
                throw new MailSendException("connection refused by test");
            }
        };
    }
}
