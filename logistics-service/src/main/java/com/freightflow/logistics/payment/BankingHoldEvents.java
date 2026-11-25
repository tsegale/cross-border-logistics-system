package com.freightflow.logistics.payment;

import com.freightflow.logistics.telemetry.TraceContext;
import com.freightflow.logistics.events.HoldEvent;
import com.freightflow.logistics.events.ProcessedEvents;
import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * What logistics does when banking changed a hold on its own (ADR 0028). Payment commands stay HTTP request and
 * response (ADR 0012): logistics knows the outcome of what it asked for. These events tell it what it did not ask
 * for.
 *
 * <ul>
 *   <li>{@code hold.expired} on a shipment still waiting for pickup on that hold: authorise again. Extensions
 *       (ADR 0011) should have kept the hold alive; this is the recovery when they could not (banking down through
 *       the renewal window, an extension parked).</li>
 *   <li>{@code hold.captured} for which logistics sent no capture: money was taken that logistics did not ask for.
 *       Nothing to repair automatically; {@code freightflow.payment.unexpected.captures} goes up and an operator
 *       looks (docs/events.md, alerting).</li>
 * </ul>
 *
 * Each event is handled once: the processed-events record commits with the effect (ADR 0026).
 */
@Component
public class BankingHoldEvents {

    private static final Logger log = LoggerFactory.getLogger(BankingHoldEvents.class);

    private final ProcessedEvents processed;
    private final ShipmentRepository shipments;
    private final PaymentCommands outbox;
    private final PaymentCommandRepository commands;
    private final HoldExpiryPolicy holdExpiry;
    private final MeterRegistry meters;
    private final Counter unexpectedCaptures;
    private final Clock clock;

    private final TraceContext traces;

    public BankingHoldEvents(ProcessedEvents processed, ShipmentRepository shipments, PaymentCommands outbox,
                             PaymentCommandRepository commands, HoldExpiryPolicy holdExpiry, MeterRegistry meters,
                             Clock clock, TraceContext traces) {
        this.traces = traces;
        this.processed = processed;
        this.shipments = shipments;
        this.outbox = outbox;
        this.commands = commands;
        this.holdExpiry = holdExpiry;
        this.meters = meters;
        this.unexpectedCaptures = Counter.builder("freightflow.payment.unexpected.captures")
                .description("Captures banking reported that logistics never sent: money taken without a pickup")
                .register(meters);
        this.clock = clock;
    }

    @Transactional
    public void handle(HoldEvent event, String topic) {
        // On the consumer span Kafka's observation continued from the record's traceparent (ADR 0030).
        traces.tag("freightflow.event.type", event.type());
        traces.tag("freightflow.event.id", event.eventId());
        traces.tag("freightflow.hold.id", event.payload().hold().holdId());
        if (!processed.firstTime(event.eventId(), topic, event.type())) {
            count(event, "duplicate");
            return;
        }
        String outcome = switch (event.type()) {
            case HoldEvent.EXPIRED -> expired(event);
            case HoldEvent.CAPTURED -> captured(event);
            // authorised, extended, released: answers to logistics' own commands, already recorded from HTTP.
            default -> "ignored";
        };
        count(event, outcome);
    }

    private String expired(HoldEvent event) {
        Optional<Shipment> found = shipment(event);
        if (found.isEmpty()) {
            return "not-ours";
        }
        Shipment shipment = found.get();
        Instant now = clock.instant();
        if (shipment.dutyHoldExpiredByBanking(event.holdId(), now)) {
            shipments.saveAndFlush(shipment);
            Instant expiresAt = holdExpiry.reauthorisationExpiry(shipment.getPlannedPickupDate(), now);
            outbox.enqueueReauthorisation(shipment.getId(), HoldKind.DUTIES, event.holdId(),
                    shipment.getDutyHoldAmount(), shipment.getDutyHoldCurrency(), shipment.getPayerAccountNumber(),
                    expiresAt);
            log.warn("banking expired duty hold {} of shipment {} before pickup; re-authorising until {}",
                    event.holdId(), shipment.getId(), expiresAt);
            return "reauthorising";
        }
        if (!shipment.holdExpiredByBanking(event.holdId(), now)) {
            // Picked up, cancelled, or already on another hold: the expiry changes nothing.
            return "no-action";
        }
        // The shipment row first, then the outbox row, as on every other path.
        shipments.saveAndFlush(shipment);
        Instant expiresAt = holdExpiry.reauthorisationExpiry(shipment.getPlannedPickupDate(), now);
        outbox.enqueueReauthorisation(shipment.getId(), event.holdId(), shipment.getQuotedTotalAmount(),
                shipment.getQuotedCurrency(), shipment.getPayerAccountNumber(), expiresAt);
        log.warn("banking expired hold {} of shipment {} before pickup; re-authorising until {}", event.holdId(),
                shipment.getId(), expiresAt);
        return "reauthorising";
    }

    private String captured(HoldEvent event) {
        Optional<Shipment> found = shipment(event);
        UUID holdId = event.holdId();
        // A shipment can have two captures (freight and duties, ADR 0038): any of its captures of this hold.
        boolean asked = found.map(shipment -> commands.findByShipmentIdOrderByCreatedAtAsc(shipment.getId()).stream()
                .anyMatch(command -> command.getCommand() == PaymentCommandType.CAPTURE
                        && holdId.equals(command.getHoldId()))).orElse(false);
        if (asked) {
            return "expected";
        }
        unexpectedCaptures.increment();
        log.error("banking captured hold {} ({} {}) for {}, but logistics sent no capture for it", holdId,
                event.payload().capturedAmount(), event.payload().hold().currency(),
                found.map(shipment -> "shipment " + shipment.getId()).orElse("no known shipment"));
        return "unexpected";
    }

    private Optional<Shipment> shipment(HoldEvent event) {
        return event.shipmentId().flatMap(shipments::findById);
    }

    private void count(HoldEvent event, String outcome) {
        meters.counter("freightflow.events.consumed", "type", event.type(), "outcome", outcome).increment();
    }
}
