package com.freightflow.logistics.invariant;

import com.freightflow.logistics.payment.PaymentCommandRelay;
import com.freightflow.logistics.support.IntegrationTest;
import com.freightflow.logistics.support.LogisticsFixtures;
import com.freightflow.logistics.support.SharedContainers;
import com.freightflow.logistics.support.TestCallers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The invariant auditor (ADR 0044) names a delivered shipment that was never captured, a duty hold that says captured
 * without a capture, and an event stuck in the outbox, and leaves a shipment paid once alone. The database is shared,
 * so the test looks for its own shipments in the report; the states are planted as the owner, as only a bug could.
 */
class InvariantAuditorIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PaymentCommandRelay relay;

    @Autowired
    private InvariantAuditor auditor;

    @Autowired
    private MeterRegistry meters;

    @Test
    void aShipmentPaidOnceHoldsAndEachPlantedBreakIsNamed() throws Exception {
        LogisticsFixtures fixtures = new LogisticsFixtures(mockMvc, relay);
        JsonNode paid = fixtures.bookAndPay();
        JsonNode unpaid = fixtures.bookAndPay();
        JsonNode duties = fixtures.bookAndPay();
        JdbcTemplate owner = SharedContainers.owner();

        // Delivered, its freight captured once.
        delivered(owner, paid);
        captured(owner, paid);
        // Delivered, and banking never confirmed its capture, long past the grace period.
        delivered(owner, unpaid);
        // A duty hold that says captured, with no capture behind it.
        delivered(owner, duties);
        captured(owner, duties);
        owner.update("""
                UPDATE shipments SET duty_hold_status = 'CAPTURED', duty_hold_amount = 10.0000,
                                     duty_hold_currency = quoted_currency, customs_disbursement_amount = 0
                WHERE id = ?::uuid
                """, id(duties));
        // An event that never left, an hour ago.
        UUID aggregate = UUID.randomUUID();
        owner.update("INSERT INTO event_sequences (aggregate_id, last_sequence) VALUES (?, 1)", aggregate);
        owner.update("""
                INSERT INTO event_outbox (id, topic, event_type, aggregate_id, sequence, envelope, occurred_at,
                                          next_attempt_at)
                VALUES (gen_random_uuid(), 'freightflow.shipment.v1', 'shipment.booked', ?, 1, '{}',
                        now() - interval '1 hour', now() + interval '1 day')
                """, aggregate);
        try {
            InvariantReport report = auditor.audit();
            assertThat(report.broken()).contains("shipments-paid-once", "outbox-in-order");
            assertThat(meters.get("freightflow.invariants.violations").tag("invariant", "outbox-in-order").gauge().value())
                    .isPositive();
            // Every row, not only the report's first five: other tests' shipments share this database.
            List<String> unpaidOnes = auditor.violations("shipments-paid-once");
            assertThat(unpaidOnes).noneSatisfy(row -> assertThat(row).contains(tracking(paid)));
            assertThat(unpaidOnes).anySatisfy(row -> assertThat(row).contains(tracking(unpaid)).contains("0 freight captures"));
            assertThat(unpaidOnes).anySatisfy(row -> assertThat(row).contains(tracking(duties)).contains("0 duty captures"));
            assertThat(auditor.violations("outbox-in-order")).anySatisfy(row -> assertThat(row).contains("unpublished"));

            mockMvc.perform(get("/api/v1/logistics/invariants").with(TestCallers.as(TestCallers.AUDITOR)))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.holds").value(false));
        } finally {
            owner.update("DELETE FROM event_outbox WHERE aggregate_id = ?", aggregate);
            owner.update("DELETE FROM event_sequences WHERE aggregate_id = ?", aggregate);
        }
    }

    /** A freight capture banking confirmed: the shipment's own AUTHORISE row, turned into its CAPTURE. */
    private static void captured(JdbcTemplate owner, JsonNode shipment) {
        String id = id(shipment);
        owner.execute("""
                CREATE TEMP TABLE planted AS SELECT * FROM shipment_payments
                    WHERE shipment_id = '%1$s' AND command = 'AUTHORISE' AND hold_kind = 'FREIGHT';
                UPDATE planted SET id = gen_random_uuid(), command = 'CAPTURE', status = 'SUCCEEDED',
                    idempotency_key = 'audit-%1$s', hold_id = (SELECT hold_id FROM shipments WHERE id = '%1$s'),
                    next_attempt_at = now(), completed_at = now(), updated_at = now();
                INSERT INTO shipment_payments SELECT * FROM planted;
                DROP TABLE planted;
                """.formatted(id));
    }

    private static void delivered(JdbcTemplate owner, JsonNode shipment) {
        owner.update("""
                UPDATE shipments SET status = 'DELIVERED', payment_status = 'CAPTURED',
                                     updated_at = now() - interval '1 hour'
                WHERE id = ?::uuid
                """, id(shipment));
    }

    private static List<String> examples(InvariantReport report, String name) {
        return report.invariants().stream().filter(invariant -> invariant.name().equals(name)).findFirst()
                .orElseThrow().examples();
    }

    private static String id(JsonNode shipment) {
        return shipment.get("id").asString();
    }

    private static String tracking(JsonNode shipment) {
        return shipment.get("trackingNumber").asString();
    }
}
