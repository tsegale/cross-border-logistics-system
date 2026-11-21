package com.freightflow.notification.shipment;

import com.freightflow.notification.events.ConsumedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

/**
 * What notification-service knows of each shipment, built from logistics' events (ADR 0027): its tracking number
 * and organisations, to name it in an email about its payment, and the last {@code sequence} applied, to tell a stale
 * status event from a new one (ADR 0026).
 *
 * <p>Retry topics reorder events: a status change that failed once comes back after later ones. Applying only a
 * sequence above the last one keeps the projection, and the emails, on the newest status; the late event is stale.
 */
@Component
public class ShipmentProjection {

    public record KnownShipment(UUID shipmentId, String trackingNumber, UUID organizationId, UUID payerOrganizationId,
                                String status, long lastSequence) {
    }

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public ShipmentProjection(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    public Optional<KnownShipment> find(UUID shipmentId) {
        return jdbcTemplate.query("""
                SELECT shipment_id, tracking_number, organization_id, payer_organization_id, status, last_sequence
                FROM shipments WHERE shipment_id = ?
                """, (rs, row) -> new KnownShipment(rs.getObject("shipment_id", UUID.class),
                rs.getString("tracking_number"), rs.getObject("organization_id", UUID.class),
                rs.getObject("payer_organization_id", UUID.class), rs.getString("status"),
                rs.getLong("last_sequence")), shipmentId).stream().findFirst();
    }

    /**
     * Applies a shipment event that carries the shipment's status. Returns false if the projection already reflects
     * this sequence or a later one: the event is stale.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean apply(ConsumedEvent event) {
        ConsumedEvent.Payload p = event.payload();
        // Insert if unknown, otherwise move forward only: a row is never set back to an older sequence.
        return jdbcTemplate.update("""
                INSERT INTO shipments (shipment_id, tracking_number, organization_id, payer_organization_id, status,
                                       last_sequence, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (shipment_id) DO UPDATE
                    SET status = EXCLUDED.status, last_sequence = EXCLUDED.last_sequence, updated_at = EXCLUDED.updated_at
                    WHERE shipments.last_sequence < EXCLUDED.last_sequence
                """, p.shipmentId(), p.trackingNumber(), p.organizationId(), p.payerOrganizationId(), p.status(),
                event.sequence(), Timestamp.from(clock.instant())) == 1;
    }
}
