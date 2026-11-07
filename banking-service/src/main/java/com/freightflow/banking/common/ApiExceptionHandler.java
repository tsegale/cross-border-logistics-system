package com.freightflow.banking.common;

import com.freightflow.banking.account.AccountNotActiveException;
import com.freightflow.banking.account.AccountNotFoundException;
import com.freightflow.banking.account.InsufficientFundsException;
import com.freightflow.banking.account.InvalidAccountNumberException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
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
 * Every error leaves banking-service as RFC 9457 Problem Details (application/problem+json)
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

    @ExceptionHandler(InvalidAccountNumberException.class)
    public ProblemDetail handleInvalidAccountNumber(InvalidAccountNumberException ex) {
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "invalid-account-number",
                "Invalid account number",
                ex.getMessage()
        );
        if (ex.getAccountNumber() != null) {
            problem.setProperty("accountNumber", ex.getAccountNumber());
        }
        return problem;
    }

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail handleAccountNotFound(AccountNotFoundException ex) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "account-not-found",
                "Account not found",
                ex.getMessage()
        );
        problem.setProperty("account", ex.getIdentifier());
        return problem;
    }

    @ExceptionHandler(AccountNotActiveException.class)
    public ProblemDetail handleAccountNotActive(AccountNotActiveException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "account-not-active",
                "Account not active",
                ex.getMessage()
        );
        problem.setProperty("accountNumber", ex.getAccountNumber());
        problem.setProperty("status", ex.getStatus().name());
        return problem;
    }

    @ExceptionHandler(InsufficientFundsException.class)
    public ProblemDetail handleInsufficientFunds(InsufficientFundsException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "insufficient-funds",
                "Insufficient funds",
                ex.getMessage()
        );
        problem.setProperty("accountNumber", ex.getAccountNumber());
        problem.setProperty("currency", ex.getCurrency());
        problem.setProperty("availableBalance", ex.getAvailableBalance());
        problem.setProperty("requestedAmount", ex.getRequestedAmount());
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

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDatabaseUnavailable(Exception ex) {
        log.error("Database unavailable: {}", ex.getMessage());
        ProblemDetail problem = problem(
                HttpStatus.SERVICE_UNAVAILABLE,
                "database-unavailable",
                "Temporarily unavailable",
                "The ledger database cannot be reached right now. Try again shortly."
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
