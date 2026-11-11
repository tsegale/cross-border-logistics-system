package com.freightflow.logistics.payment;

import com.freightflow.logistics.banking.BankingApi;
import com.freightflow.logistics.banking.BankingCalls;
import com.freightflow.logistics.banking.Holds;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.Set;

/**
 * Sends one payment command to banking-service and classifies the answer (ADR 0012). Nothing here throws for an
 * HTTP or network failure: every result is a {@link PaymentOutcome} the relay can record.
 */
@Component
public class BankingPayments {

    private static final Logger log = LoggerFactory.getLogger(BankingPayments.class);
    private static final String PROBLEMS = "https://freightflow.dev/problems/";
    /** 409s that mean "try again", not "no": the request was not processed and the same key is safe to resend. */
    private static final Set<String> RETRYABLE_CONFLICTS = Set.of(
            PROBLEMS + "idempotency-key-in-flight", PROBLEMS + "transaction-conflict");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final BankingApi banking;
    private final BankingCalls calls;

    public BankingPayments(BankingApi banking, BankingCalls calls) {
        this.banking = banking;
        this.calls = calls;
    }

    public PaymentOutcome send(PaymentCommand command) {
        try {
            Holds.Hold hold = calls.payment(() -> switch (command.getCommand()) {
                case AUTHORISE -> banking.createHold(command.getIdempotencyKey(), new Holds.CreateHold(
                        command.getPayerAccountNumber(), command.roundedAmount(), command.getCurrency(),
                        "SHIPMENT " + command.getShipmentId(), command.getExpiresAt(),
                        command.getPayerOrganizationId(), command.getOnBehalfOfSubject()));
                case CAPTURE -> banking.capture(command.getHoldId(), command.getIdempotencyKey(),
                        new Holds.CaptureHold(command.roundedAmount()));
                case RELEASE -> banking.release(command.getHoldId(), command.getIdempotencyKey());
                case EXTEND -> banking.extend(command.getHoldId(), command.getIdempotencyKey(),
                        new Holds.ExtendHold(command.getExpiresAt()));
            });
            if (hold == null) {
                return new PaymentOutcome.Succeeded(command.getHoldId(), null);
            }
            return new PaymentOutcome.Succeeded(hold.id(), hold.expiresAt());
        } catch (CallNotPermittedException | BulkheadFullException | OAuth2AuthorizationException ex) {
            // The breaker is open, the bulkhead full, or no token could be obtained from Keycloak (ADR 0019): the
            // request was never sent to banking, so this is not an attempt and the command stays PENDING.
            return new PaymentOutcome.Unknown(ex.getClass().getSimpleName() + ": " + ex.getMessage(), false);
        } catch (HttpStatusCodeException ex) {
            return classify(command, ex);
        } catch (RestClientException ex) {
            // Connect or read timeout, connection reset, unreadable response: banking may have committed.
            return new PaymentOutcome.Unknown(ex.getClass().getSimpleName() + ": " + ex.getMessage(), true);
        }
    }

    private PaymentOutcome classify(PaymentCommand command, HttpStatusCodeException ex) {
        int status = ex.getStatusCode().value();
        JsonNode problem = problem(ex);
        String type = problem.path("type").asString("");
        String detail = problem.path("detail").asString(ex.getStatusText());
        if (status >= 500 || status == 408 || status == 429 || RETRYABLE_CONFLICTS.contains(type)) {
            return new PaymentOutcome.Unknown("HTTP " + status + " " + type, true);
        }
        // A capture or release that already happened (its first response was lost and the idempotency record has
        // since expired) reports the hold as no longer active: that is the outcome we wanted. An extension of a
        // hold that was captured or released meanwhile has nothing left to do.
        if ((PROBLEMS + "hold-not-active").equals(type)) {
            String holdStatus = problem.path("holdStatus").asString("");
            boolean done = switch (command.getCommand()) {
                case CAPTURE -> "CAPTURED".equals(holdStatus);
                case RELEASE -> "RELEASED".equals(holdStatus) || "EXPIRED".equals(holdStatus);
                case EXTEND -> "CAPTURED".equals(holdStatus) || "RELEASED".equals(holdStatus);
                case AUTHORISE -> false;
            };
            if (done) {
                log.info("{} for shipment {} was already applied or is moot (hold {})", command.getCommand(),
                        command.getShipmentId(), holdStatus);
                return new PaymentOutcome.Succeeded(command.getHoldId(), null);
            }
        }
        return new PaymentOutcome.Refused(type.isEmpty() ? "HTTP " + status : type, detail);
    }

    private static JsonNode problem(HttpStatusCodeException ex) {
        try {
            String body = ex.getResponseBodyAsString();
            return body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body);
        } catch (RuntimeException unreadable) {
            return JSON.createObjectNode();
        }
    }
}
