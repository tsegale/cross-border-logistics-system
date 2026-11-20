package com.freightflow.notification.notify;

import com.freightflow.notification.events.ConsumedEvent;
import com.freightflow.notification.events.ProcessedEvents;
import com.freightflow.notification.settings.NotificationSettings;
import com.freightflow.notification.settings.SettingsStore;
import com.freightflow.notification.shipment.ShipmentProjection;
import com.freightflow.notification.shipment.ShipmentProjection.KnownShipment;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Turns shipment and hold events into emails (ADR 0027), once per event (ADR 0026): the processed-events record, the
 * projection update and the notification rows commit together, so a redelivered or replayed event writes nothing.
 * Sending is {@link EmailSender}'s job.
 */
@Component
public class EventNotifier {

    private static final Logger log = LoggerFactory.getLogger(EventNotifier.class);
    private static final String PROBLEMS = "https://freightflow.dev/problems/";

    private final ProcessedEvents processed;
    private final ShipmentProjection shipments;
    private final SettingsStore settings;
    private final NotificationStore notifications;
    private final EmailRenderer renderer;
    private final MessageSource messages;
    private final MeterRegistry meters;

    public EventNotifier(ProcessedEvents processed, ShipmentProjection shipments, SettingsStore settings,
                         NotificationStore notifications, EmailRenderer renderer, MessageSource messages,
                         MeterRegistry meters) {
        this.processed = processed;
        this.shipments = shipments;
        this.settings = settings;
        this.notifications = notifications;
        this.renderer = renderer;
        this.messages = messages;
        this.meters = meters;
    }

    /** A shipment has not reached the projection yet: retried through the retry topics until it has. */
    public static class ShipmentNotYetKnownException extends RuntimeException {
        public ShipmentNotYetKnownException(UUID shipmentId) {
            super("Shipment " + shipmentId + " is not known yet; its booking event has not been consumed");
        }
    }

    @Transactional
    public void onEvent(ConsumedEvent event, String topic) {
        if (!processed.firstTime(event.eventId(), topic, event.type())) {
            count(event, "duplicate");
            return;
        }
        String outcome = switch (event.type()) {
            case "shipment.booked" -> booked(event);
            case "shipment.status-changed" -> statusChanged(event);
            case "shipment.payment-failed" -> paymentFailed(event);
            case "hold.captured" -> holdClosed(event, NotificationKind.PAYMENT_CAPTURED);
            case "hold.released", "hold.expired" -> holdClosed(event, NotificationKind.PAYMENT_RELEASED);
            // tracking-event.recorded, hold.authorised, hold.extended: no email.
            default -> "ignored";
        };
        count(event, outcome);
    }

    private String booked(ConsumedEvent event) {
        ConsumedEvent.Payload p = event.payload();
        // Sequence 1: it may be older than a status change consumed first (retry topics reorder), but a booking
        // is always worth its email.
        shipments.apply(event);
        return notify(event, NotificationKind.SHIPMENT_BOOKED, p.organizationId(), p.shipmentId(), (locale, zone) ->
                new Rendering(new Object[] {p.trackingNumber(), p.origin(), p.destination()}, List.of(
                        new EmailRenderer.Fact("fact.tracking-number", p.trackingNumber()),
                        new EmailRenderer.Fact("fact.route", p.origin() + " → " + p.destination()),
                        new EmailRenderer.Fact("fact.amount", Formats.money(ConsumedEvent.decimal(
                                p.quotedTotal().amount()), p.quotedTotal().currency(), locale)),
                        new EmailRenderer.Fact("fact.pickup", p.plannedPickupDate() == null ? "-"
                                : Formats.date(p.plannedPickupDate(), locale)))));
    }

    private String statusChanged(ConsumedEvent event) {
        ConsumedEvent.Payload p = event.payload();
        if (!shipments.apply(event)) {
            // A later status of this shipment was applied already: this one is out of date, no email (ADR 0026).
            return "stale";
        }
        if (!NotificationKind.EMAILED_STATUSES.contains(p.status())) {
            return "no-email";
        }
        return notify(event, NotificationKind.SHIPMENT_STATUS, p.organizationId(), p.shipmentId(), (locale, zone) -> {
            String status = messages.getMessage("status." + p.status(), null, p.status(), locale);
            return new Rendering(new Object[] {p.trackingNumber(), status}, List.of(
                    new EmailRenderer.Fact("fact.tracking-number", p.trackingNumber()),
                    new EmailRenderer.Fact("fact.status", status),
                    new EmailRenderer.Fact("fact.date", Formats.date(event.occurredAt(), locale, zone))));
        });
    }

    private String paymentFailed(ConsumedEvent event) {
        ConsumedEvent.Payload p = event.payload();
        // The organisation that pays is the one whose money failed.
        return notify(event, NotificationKind.PAYMENT_FAILED, p.payerOrganizationId(), p.shipmentId(), (locale, zone) -> {
            String reason = problem(p.problemType(), locale);
            return new Rendering(new Object[] {p.trackingNumber(), reason}, List.of(
                    new EmailRenderer.Fact("fact.tracking-number", p.trackingNumber()),
                    new EmailRenderer.Fact("fact.amount",
                            Formats.money(ConsumedEvent.decimal(p.amount()), p.currency(), locale))));
        });
    }

    private String holdClosed(ConsumedEvent event, NotificationKind kind) {
        ConsumedEvent.Hold hold = event.payload().hold();
        UUID shipmentId = event.holdShipmentId().orElse(null);
        if (hold == null || shipmentId == null) {
            return "not-a-shipment";
        }
        // The booking event is on another topic and may not be consumed yet: retry until it is.
        KnownShipment shipment = shipments.find(shipmentId)
                .orElseThrow(() -> new ShipmentNotYetKnownException(shipmentId));
        BigDecimal amount = ConsumedEvent.decimal(kind == NotificationKind.PAYMENT_CAPTURED
                ? event.payload().capturedAmount() : hold.amount());
        return notify(event, kind, hold.organizationId(), shipmentId, (locale, zone) -> {
            String money = Formats.money(amount, hold.currency(), locale);
            return new Rendering(new Object[] {shipment.trackingNumber(), money}, List.of(
                    new EmailRenderer.Fact("fact.tracking-number", shipment.trackingNumber()),
                    new EmailRenderer.Fact("fact.amount", money),
                    new EmailRenderer.Fact("fact.date", Formats.date(event.occurredAt(), locale, zone))));
        });
    }

    private record Rendering(Object[] args, List<EmailRenderer.Fact> facts) {
    }

    private interface Content {
        Rendering in(Locale locale, ZoneId zone);
    }

    /** Renders the email in the organisation's language and writes one row per recipient. */
    private String notify(ConsumedEvent event, NotificationKind kind, UUID organizationId, UUID shipmentId,
                          Content content) {
        if (organizationId == null) {
            return "no-organisation";
        }
        NotificationSettings wanted = settings.find(organizationId);
        if (!wanted.wants(kind)) {
            return "not-wanted";
        }
        Rendering rendering = content.in(wanted.locale(), wanted.timeZone());
        EmailRenderer.Email email = renderer.render(kind, wanted.locale(), rendering.args(), rendering.facts());
        List<String> added = new ArrayList<>();
        for (String recipient : wanted.recipients()) {
            if (notifications.add(event.eventId(), event.type(), kind, organizationId, shipmentId, recipient,
                    wanted.language(), email)) {
                added.add(recipient);
            }
        }
        log.info("{} for event {} ({}): {} email(s) queued in {}", kind.code(), event.eventId(), event.type(),
                added.size(), wanted.language());
        return "queued";
    }

    private String problem(String type, Locale locale) {
        String slug = type != null && type.startsWith(PROBLEMS) ? type.substring(PROBLEMS.length()) : "other";
        String fallback = messages.getMessage("problem.other", null, locale);
        return messages.getMessage("problem." + slug, null, fallback, locale);
    }

    private void count(ConsumedEvent event, String outcome) {
        meters.counter("freightflow.events.consumed", "type", event.type(), "outcome", outcome).increment();
    }
}
