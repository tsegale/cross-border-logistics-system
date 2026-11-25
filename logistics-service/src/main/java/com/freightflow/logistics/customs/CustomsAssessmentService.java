package com.freightflow.logistics.customs;

import com.freightflow.logistics.common.MoneyRounding;
import com.freightflow.logistics.common.UuidV7;
import com.freightflow.logistics.customs.CustomsExceptions.CustomsAssessmentConflictException;
import com.freightflow.logistics.customs.CustomsExceptions.CustomsAssessmentNotFoundException;
import com.freightflow.logistics.customs.CustomsExceptions.CustomsFourEyesException;
import com.freightflow.logistics.payment.PaymentCommands;
import com.freightflow.logistics.security.Actor;
import com.freightflow.logistics.security.Caller;
import com.freightflow.logistics.shipment.PaymentStatus;
import com.freightflow.logistics.shipment.Shipment;
import com.freightflow.logistics.shipment.ShipmentNotFoundException;
import com.freightflow.logistics.shipment.ShipmentRepository;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The customs authority's assessment of a shipment whose duties FreightFlow collected (ADR 0036), four eyes
 * (ADR 0021): one operator records what the authority's notice says, a second confirms it, and the confirmation sends
 * the settlement to banking through the payment outbox (ADR 0012): customs is paid what it assessed, and the payer is
 * charged or refunded the difference from the estimate the capture put aside.
 */
@Service
public class CustomsAssessmentService {

    private static final Logger log = LoggerFactory.getLogger(CustomsAssessmentService.class);

    private final ShipmentRepository shipments;
    private final PaymentCommands paymentCommands;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    public CustomsAssessmentService(ShipmentRepository shipments, PaymentCommands paymentCommands,
                                    JdbcTemplate jdbcTemplate, Clock clock) {
        this.shipments = shipments;
        this.paymentCommands = paymentCommands;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    @Schema(description = "What the customs authority's notice says (ADR 0036)")
    public record AssessmentRequest(
            @Schema(example = "0.00") @NotNull(message = "duties is required")
            @DecimalMin(value = "0.00", message = "duties must not be negative") BigDecimal duties,
            @Schema(example = "3611.34") @NotNull(message = "importVat is required")
            @DecimalMin(value = "0.00", message = "importVat must not be negative") BigDecimal importVat,
            @Schema(example = "EUR") @NotBlank(message = "currency is required")
            @Pattern(regexp = "^[A-Z]{3}$", message = "currency must be an ISO 4217 code") String currency,
            @Schema(description = "The customs declaration or notice number", example = "26DE470100123456E1")
            @NotBlank(message = "authorityReference is required")
            @Size(max = 64, message = "authorityReference must be at most 64 characters") String authorityReference
    ) {
    }

    public record AssessmentResponse(UUID id, UUID shipmentId, String status, String currency, BigDecimal duties,
                                     BigDecimal importVat, BigDecimal assessed, BigDecimal estimate,
                                     @Schema(description = "assessed - estimate: charged when positive, refunded "
                                             + "when negative") BigDecimal difference,
                                     String authorityReference, String recordedBy, Instant recordedAt,
                                     @Schema(nullable = true) String confirmedBy,
                                     @Schema(nullable = true) Instant confirmedAt) {
    }

    @Transactional
    public AssessmentResponse record(Caller caller, UUID shipmentId, AssessmentRequest request) {
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(() -> new ShipmentNotFoundException(shipmentId.toString()));
        if (shipment.getCustomsDisbursementAmount().signum() == 0 && !shipment.hasDutyHold()) {
            throw new CustomsAssessmentConflictException(shipmentId,
                    "FreightFlow did not collect duties with this shipment; its payer settles them with customs");
        }
        // In the freight hold, in the quote currency (ADR 0036), or in the duty hold, in its own (ADR 0038).
        String collectedIn = shipment.hasDutyHold() ? shipment.getDutyHoldCurrency() : shipment.getQuotedCurrency();
        BigDecimal estimate = shipment.hasDutyHold() ? shipment.getDutyHoldAmount()
                : shipment.getCustomsDisbursementAmount();
        if (!request.currency().equals(collectedIn)) {
            throw new CustomsAssessmentConflictException(shipmentId, "the duties were collected in "
                    + collectedIn + ", not " + request.currency());
        }
        String currency = request.currency();
        BigDecimal duties = MoneyRounding.normalizeCustomerAmount(request.duties(), currency);
        BigDecimal vat = MoneyRounding.normalizeCustomerAmount(request.importVat(), currency);
        Actor maker = caller.actor();
        Instant now = clock.instant();
        int inserted = jdbcTemplate.update("""
                INSERT INTO customs_assessments (id, shipment_id, status, currency, duties, import_vat, estimate,
                                                 authority_reference, recorded_by_subject, recorded_by_client_id,
                                                 recorded_at)
                VALUES (?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (shipment_id) DO NOTHING
                """, UuidV7.next(clock), shipmentId, currency, duties, vat, estimate,
                request.authorityReference().trim(), maker.subject(), maker.clientId(), Timestamp.from(now));
        if (inserted == 0) {
            throw new CustomsAssessmentConflictException(shipmentId, "an assessment is already recorded");
        }
        log.info("Customs assessment recorded for shipment {} by {}: {} {}", shipmentId, maker.subject(),
                duties.add(vat), currency);
        return get(shipmentId);
    }

    /** The second operator confirms; banking settles once the hold is captured. */
    @Transactional
    public AssessmentResponse confirm(Caller caller, UUID shipmentId) {
        List<String> recordedBy = jdbcTemplate.queryForList("""
                SELECT recorded_by_subject FROM customs_assessments WHERE shipment_id = ? AND status = 'PENDING'
                FOR UPDATE
                """, String.class, shipmentId);
        if (recordedBy.isEmpty()) {
            get(shipmentId);
            throw new CustomsAssessmentConflictException(shipmentId, "the assessment is already confirmed");
        }
        Actor checker = caller.actor();
        if (recordedBy.getFirst().equals(checker.subject())) {
            throw new CustomsFourEyesException(shipmentId);
        }
        Shipment shipment = shipments.findById(shipmentId).orElseThrow(() -> new ShipmentNotFoundException(shipmentId.toString()));
        boolean dutyHold = shipment.hasDutyHold();
        PaymentStatus collected = dutyHold ? shipment.getDutyHoldStatus() : shipment.getPaymentStatus();
        UUID hold = dutyHold ? shipment.getDutyHoldId() : shipment.getHoldId();
        if (collected != PaymentStatus.CAPTURED || hold == null) {
            throw new CustomsAssessmentConflictException(shipmentId, "the " + (dutyHold ? "duty hold" : "payment")
                    + " is " + collected + "; customs is settled after the capture");
        }
        AssessmentResponse assessment = get(shipmentId);
        jdbcTemplate.update("""
                UPDATE customs_assessments SET status = 'CONFIRMED', confirmed_by_subject = ?,
                       confirmed_by_client_id = ?, confirmed_at = ?
                WHERE shipment_id = ? AND status = 'PENDING'
                """, checker.subject(), checker.clientId(), Timestamp.from(clock.instant()), shipmentId);
        paymentCommands.enqueueCustomsSettlement(shipmentId,
                dutyHold ? com.freightflow.logistics.payment.HoldKind.DUTIES
                        : com.freightflow.logistics.payment.HoldKind.FREIGHT,
                hold, assessment.assessed(), assessment.currency(), shipment.getPayerAccountNumber(),
                assessment.authorityReference());
        log.info("Customs assessment of shipment {} confirmed by {}: settlement queued", shipmentId, checker.subject());
        return get(shipmentId);
    }

    @Transactional(readOnly = true)
    public AssessmentResponse get(UUID shipmentId) {
        return jdbcTemplate.query("""
                SELECT id, shipment_id, status, currency, duties, import_vat, estimate, authority_reference,
                       recorded_by_subject, recorded_at, confirmed_by_subject, confirmed_at
                FROM customs_assessments WHERE shipment_id = ?
                """, (rs, row) -> {
            String currency = rs.getString("currency");
            BigDecimal duties = MoneyRounding.roundToMinorUnits(rs.getBigDecimal("duties"), currency);
            BigDecimal vat = MoneyRounding.roundToMinorUnits(rs.getBigDecimal("import_vat"), currency);
            BigDecimal estimate = MoneyRounding.roundToMinorUnits(rs.getBigDecimal("estimate"), currency);
            Timestamp confirmedAt = rs.getTimestamp("confirmed_at");
            return new AssessmentResponse(rs.getObject("id", UUID.class), shipmentId, rs.getString("status"), currency,
                    duties, vat, duties.add(vat), estimate, duties.add(vat).subtract(estimate),
                    rs.getString("authority_reference"), rs.getString("recorded_by_subject"),
                    rs.getTimestamp("recorded_at").toInstant(), rs.getString("confirmed_by_subject"),
                    confirmedAt == null ? null : confirmedAt.toInstant());
        }, shipmentId).stream().findFirst().orElseThrow(() -> new CustomsAssessmentNotFoundException(shipmentId));
    }
}
