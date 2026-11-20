package com.freightflow.notification.common;

import com.freightflow.notification.deadletter.DeadLetterExceptions.DeadLetterAlreadyRedrivenException;
import com.freightflow.notification.deadletter.DeadLetterExceptions.DeadLetterNotFoundException;
import com.freightflow.notification.deadletter.DeadLetterExceptions.RedriveFailedException;
import com.freightflow.notification.settings.SettingsExceptions.InvalidSettingsException;
import com.freightflow.notification.settings.SettingsExceptions.SettingsChangedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.CannotCreateTransactionException;
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
 * Every error leaves notification-service as RFC 9457 Problem Details with a stable {@code type} URI listed in
 * {@code docs/problems.md}.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    public static final String PROBLEM_BASE = "https://freightflow.dev/problems/";

    public record FieldValidationError(String field, String message) {
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        List<FieldValidationError> errors = ex.getBindingResult().getFieldErrors().stream()
                .sorted(Comparator.comparing(FieldError::getField))
                .map(error -> new FieldValidationError(error.getField(), error.getDefaultMessage()))
                .toList();
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation-failed", "Request validation failed",
                "One or more request fields failed validation");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status,
                                                                  WebRequest request) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "invalid-request-body", "Invalid request body",
                "Request body could not be parsed or contains an invalid enum value");
        return handleExceptionInternal(ex, problem, headers, HttpStatus.BAD_REQUEST, request);
    }

    @ExceptionHandler(InvalidSettingsException.class)
    public ProblemDetail handleInvalidSettings(InvalidSettingsException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-notification-settings", "Invalid notification settings",
                ex.getMessage());
    }

    @ExceptionHandler(SettingsChangedException.class)
    public ProblemDetail handleSettingsChanged(SettingsChangedException ex) {
        ProblemDetail problem = problem(HttpStatus.PRECONDITION_FAILED, "precondition-failed", "Precondition failed",
                ex.getMessage());
        problem.setProperty("expectedETag", ex.expected());
        problem.setProperty("currentETag", ex.current());
        return problem;
    }

    @ExceptionHandler(DeadLetterNotFoundException.class)
    public ProblemDetail handleDeadLetterNotFound(DeadLetterNotFoundException ex) {
        return problem(HttpStatus.NOT_FOUND, "dead-letter-not-found", "Dead letter not found", ex.getMessage());
    }

    @ExceptionHandler(DeadLetterAlreadyRedrivenException.class)
    public ProblemDetail handleAlreadyRedriven(DeadLetterAlreadyRedrivenException ex) {
        return problem(HttpStatus.CONFLICT, "dead-letter-already-redriven", "Dead letter already redriven",
                ex.getMessage());
    }

    @ExceptionHandler(RedriveFailedException.class)
    public ProblemDetail handleRedriveFailed(RedriveFailedException ex) {
        ProblemDetail problem = problem(HttpStatus.CONFLICT, "dead-letter-redrive-failed", "Redrive failed",
                ex.getMessage());
        problem.setProperty("redriveAttempts", ex.attempts());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException ex) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-argument", "Invalid request argument", ex.getMessage());
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "database-unavailable", "Temporarily unavailable",
                        "The notification database cannot be reached right now. Try again shortly."));
    }

    /** A role or organisation check in a service refused the request (ADR 0018). */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(problem(HttpStatus.FORBIDDEN, "forbidden", "Forbidden", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                        "The request could not be completed."));
    }

    public static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + slug));
        return problem;
    }
}
