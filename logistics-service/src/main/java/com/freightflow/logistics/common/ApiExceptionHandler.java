package com.freightflow.logistics.common;

import com.freightflow.logistics.idempotency.IdempotencyKeyInFlightException;
import com.freightflow.logistics.idempotency.IdempotencyPayloadMismatchException;
import com.freightflow.logistics.idempotency.MissingIdempotencyKeyException;
import com.freightflow.logistics.location.UnknownLocationException;
import com.freightflow.logistics.quote.InvalidRouteException;
import com.freightflow.logistics.quote.NothingToQuoteException;
import com.freightflow.logistics.quote.QuoteNotFoundException;
import com.freightflow.logistics.shipment.InvalidAccountNumberException;
import com.freightflow.logistics.shipment.InvalidShipmentCursorException;
import com.freightflow.logistics.shipment.InvalidShipmentStateTransitionException;
import com.freightflow.logistics.shipment.InvalidTrackingNumberException;
import com.freightflow.logistics.shipment.PreconditionFailedException;
import com.freightflow.logistics.shipment.QuoteExpiredException;
import com.freightflow.logistics.shipment.ShipmentNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.net.URI;
import java.util.Comparator;
import java.util.List;

/**
 * Every error leaves logistics-service as RFC 9457 Problem Details (application/problem+json)
 * with a stable {@code type} URI listed in {@code docs/problems.md}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public static final String PROBLEM_BASE = "https://freightflow.dev/problems/";

    public record FieldValidationError(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        List<FieldValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(err -> new FieldValidationError(err.getField(), err.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "validation-failed",
                "Request validation failed",
                "One or more request fields failed validation"
        );
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex,
            HttpHeaders headers,
            HttpStatusCode status,
            WebRequest request) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "invalid-request-body",
                "Invalid request body",
                "Request body could not be parsed or contains an invalid enum value"
        );
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(UnknownLocationException.class)
    public ProblemDetail handleUnknownLocation(UnknownLocationException ex) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "unknown-location",
                "Unknown UN/LOCODE location",
                ex.getMessage()
        );
        problem.setProperty("field", ex.getField());
        problem.setProperty("unlocode", ex.getUnlocode());
        return problem;
    }

    @ExceptionHandler(InvalidRouteException.class)
    public ProblemDetail handleInvalidRoute(InvalidRouteException ex) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                ex.getSlug(),
                ex.getTitle(),
                ex.getMessage()
        );
        problem.setProperty("origin", ex.getOrigin());
        problem.setProperty("destination", ex.getDestination());
        problem.setProperty("mode", ex.getMode());
        return problem;
    }

    @ExceptionHandler(NothingToQuoteException.class)
    public ProblemDetail handleNothingToQuote(NothingToQuoteException ex) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "nothing-to-quote",
                "Nothing to quote for this payer",
                ex.getMessage()
        );
        problem.setProperty("incoterm", ex.getIncoterm());
        problem.setProperty("payerRole", ex.getPayerRole());
        return problem;
    }

    @ExceptionHandler(QuoteNotFoundException.class)
    public ProblemDetail handleQuoteNotFound(QuoteNotFoundException ex) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "quote-not-found",
                "Freight quote not found",
                ex.getMessage()
        );
        problem.setProperty("quoteId", ex.getQuoteId());
        return problem;
    }

    @ExceptionHandler(QuoteExpiredException.class)
    public ProblemDetail handleQuoteExpired(QuoteExpiredException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "quote-expired",
                "Freight quote has expired",
                ex.getMessage()
        );
        problem.setProperty("quoteId", ex.getQuoteId());
        problem.setProperty("validUntil", ex.getValidUntil());
        return problem;
    }

    @ExceptionHandler(ShipmentNotFoundException.class)
    public ProblemDetail handleShipmentNotFound(ShipmentNotFoundException ex) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "shipment-not-found",
                "Shipment not found",
                ex.getMessage()
        );
        problem.setProperty("identifier", ex.getIdentifier());
        return problem;
    }

    @ExceptionHandler(InvalidShipmentStateTransitionException.class)
    public ProblemDetail handleInvalidShipmentStateTransition(InvalidShipmentStateTransitionException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "invalid-shipment-state-transition",
                "Invalid shipment state transition",
                ex.getMessage()
        );
        problem.setProperty("currentStatus", ex.getCurrentStatus());
        problem.setProperty("requestedStatus", ex.getRequestedStatus());
        problem.setProperty("allowedNextStatuses", ex.getAllowedNextStatuses());
        return problem;
    }

    @ExceptionHandler(InvalidTrackingNumberException.class)
    public ProblemDetail handleInvalidTrackingNumber(InvalidTrackingNumberException ex) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "invalid-tracking-number",
                "Invalid tracking number",
                ex.getMessage()
        );
        problem.setProperty("trackingNumber", ex.getTrackingNumber());
        return problem;
    }

    @ExceptionHandler(InvalidAccountNumberException.class)
    public ProblemDetail handleInvalidAccountNumber(InvalidAccountNumberException ex) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "invalid-account-number",
                "Invalid account number",
                ex.getMessage()
        );
        problem.setProperty("accountNumber", ex.getAccountNumber());
        return problem;
    }

    @ExceptionHandler(InvalidShipmentCursorException.class)
    public ProblemDetail handleInvalidShipmentCursor(InvalidShipmentCursorException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-cursor",
                "Invalid pagination cursor",
                ex.getMessage()
        );
    }

    @ExceptionHandler(PreconditionFailedException.class)
    public ProblemDetail handlePreconditionFailed(PreconditionFailedException ex) {
        ProblemDetail problem = problem(
                HttpStatus.PRECONDITION_FAILED,
                "precondition-failed",
                "Precondition failed",
                ex.getMessage()
        );
        problem.setProperty("expectedETag", ex.getExpectedETag());
        problem.setProperty("currentETag", ex.getCurrentETag());
        return problem;
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ProblemDetail handleOptimisticLockFailure(ObjectOptimisticLockingFailureException ex) {
        return problem(
                HttpStatus.PRECONDITION_FAILED,
                "precondition-failed",
                "Precondition failed",
                "Concurrent modification detected on shipment resource"
        );
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ProblemDetail handleMissingIdempotencyKey(MissingIdempotencyKeyException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "idempotency-key-missing",
                "Missing or invalid Idempotency-Key header",
                ex.getMessage()
        );
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ProblemDetail handleIdempotencyPayloadMismatch(IdempotencyPayloadMismatchException ex) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency-key-payload-mismatch",
                "Idempotency-Key reused with different request payload",
                ex.getMessage()
        );
    }

    @ExceptionHandler(IdempotencyKeyInFlightException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyKeyInFlight(IdempotencyKeyInFlightException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "idempotency-key-in-flight",
                "Request with this Idempotency-Key is currently in flight",
                ex.getMessage()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
    }

    @ExceptionHandler(UnsupportedCurrencyException.class)
    public ProblemDetail handleUnsupportedCurrency(UnsupportedCurrencyException ex) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "unsupported-currency",
                "Unsupported currency",
                ex.getMessage()
        );
        problem.setProperty("currency", ex.getCurrency());
        problem.setProperty("supportedCurrencies", ex.getSupportedCurrencies());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-argument",
                "Invalid request argument",
                ex.getMessage()
        );
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable: {}", ex.getMessage());
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "database-unavailable",
                "Temporarily unavailable",
                "The logistics database cannot be reached right now. Try again shortly."
        );
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem);
    }

    /**
     * Lock timeouts, deadlocks and serialization failures are transient: the transaction rolled back and the same
     * request can be sent again. 55P03 is 503, 40P01 and 40001 are 409; both carry Retry-After.
     */
    @ExceptionHandler({ConcurrencyFailureException.class, TransactionSystemException.class, JpaSystemException.class})
    public ResponseEntity<ProblemDetail> handleTransientDatabaseConflict(Exception ex) {
        String sqlState = SqlStates.of(ex).orElse(null);
        if (SqlStates.LOCK_NOT_AVAILABLE.equals(sqlState)) {
            log.warn("Lock timeout: {}", ex.getMessage());
            return retryable(HttpStatus.SERVICE_UNAVAILABLE, "lock-timeout", "Resource busy",
                    "A resource involved is locked by other transactions right now. Retry the same request.");
        }
        if (SqlStates.DEADLOCK_DETECTED.equals(sqlState) || SqlStates.SERIALIZATION_FAILURE.equals(sqlState)
                || (sqlState == null && ex instanceof ConcurrencyFailureException)) {
            log.warn("Transaction conflict (SQLSTATE {}): {}", sqlState, ex.getMessage());
            return retryable(HttpStatus.CONFLICT, "transaction-conflict", "Transaction conflict",
                    "The transaction conflicted with a concurrent one and was rolled back. Retry the same request.");
        }
        return handleUnexpected(ex);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                        "The request could not be completed."));
    }

    private static ResponseEntity<ProblemDetail> retryable(HttpStatus status, String slug, String title, String detail) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem(status, slug, title, detail));
    }

    public static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + slug));
        return problem;
    }
}
