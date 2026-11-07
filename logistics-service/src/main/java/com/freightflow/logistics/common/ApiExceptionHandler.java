package com.freightflow.logistics.common;

import com.freightflow.logistics.location.UnknownLocationException;
import com.freightflow.logistics.quote.InvalidRouteException;
import com.freightflow.logistics.quote.QuoteNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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

    public static ProblemDetail problem(HttpStatus status, String slug, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create(PROBLEM_BASE + slug));
        return problem;
    }
}
