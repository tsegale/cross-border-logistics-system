package com.freightflow.banking.common;

import com.freightflow.banking.account.AccountNotActiveException;
import com.freightflow.banking.account.AccountNotFoundException;
import com.freightflow.banking.account.InsufficientFundsException;
import com.freightflow.banking.account.InvalidAccountNumberException;
import com.freightflow.banking.idempotency.IdempotencyKeyInFlightException;
import com.freightflow.banking.idempotency.IdempotencyPayloadMismatchException;
import com.freightflow.banking.idempotency.MissingIdempotencyKeyException;
import com.freightflow.banking.journal.CurrencyMismatchException;
import com.freightflow.banking.journal.InvalidMonetaryAmountException;
import com.freightflow.banking.journal.InvalidStatementCursorException;
import com.freightflow.banking.journal.JournalEntryAlreadyReversedException;
import com.freightflow.banking.journal.JournalEntryNotFoundException;
import com.freightflow.banking.journal.UnbalancedJournalEntryException;
import com.freightflow.banking.transfer.InvalidTransferException;
import com.freightflow.banking.transfer.TransferNotFoundException;
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

    @ExceptionHandler(JournalEntryNotFoundException.class)
    public ProblemDetail handleJournalEntryNotFound(JournalEntryNotFoundException ex) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "journal-entry-not-found",
                "Journal entry not found",
                ex.getMessage()
        );
        problem.setProperty("journalEntryId", ex.getJournalEntryId());
        return problem;
    }

    @ExceptionHandler(TransferNotFoundException.class)
    public ProblemDetail handleTransferNotFound(TransferNotFoundException ex) {
        ProblemDetail problem = problem(
                HttpStatus.NOT_FOUND,
                "transfer-not-found",
                "Transfer not found",
                ex.getMessage()
        );
        problem.setProperty("transferId", ex.getTransferId());
        return problem;
    }

    @ExceptionHandler(InvalidTransferException.class)
    public ProblemDetail handleInvalidTransfer(InvalidTransferException ex) {
        return problem(ex.getStatus(), ex.getSlug(), ex.getTitle(), ex.getMessage());
    }

    @ExceptionHandler(JournalEntryAlreadyReversedException.class)
    public ProblemDetail handleJournalEntryAlreadyReversed(JournalEntryAlreadyReversedException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "journal-entry-already-reversed",
                "Journal entry already reversed",
                ex.getMessage()
        );
        problem.setProperty("journalEntryId", ex.getJournalEntryId());
        return problem;
    }

    @ExceptionHandler(UnbalancedJournalEntryException.class)
    public ProblemDetail handleUnbalancedJournalEntry(UnbalancedJournalEntryException ex) {
        return problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "unbalanced-journal-entry",
                "Unbalanced journal entry",
                ex.getMessage()
        );
    }

    @ExceptionHandler(CurrencyMismatchException.class)
    public ProblemDetail handleCurrencyMismatch(CurrencyMismatchException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "currency-mismatch",
                "Currency mismatch",
                ex.getMessage()
        );
        problem.setProperty("expectedCurrency", ex.getExpectedCurrency());
        problem.setProperty("actualCurrency", ex.getActualCurrency());
        return problem;
    }

    @ExceptionHandler(InvalidMonetaryAmountException.class)
    public ProblemDetail handleInvalidAmount(InvalidMonetaryAmountException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-amount",
                "Invalid monetary amount",
                ex.getMessage()
        );
    }

    @ExceptionHandler(InvalidStatementCursorException.class)
    public ProblemDetail handleInvalidCursor(InvalidStatementCursorException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "invalid-cursor",
                "Invalid pagination cursor",
                ex.getMessage()
        );
    }

    @ExceptionHandler(MissingIdempotencyKeyException.class)
    public ProblemDetail handleMissingIdempotencyKey(MissingIdempotencyKeyException ex) {
        return problem(
                HttpStatus.BAD_REQUEST,
                "idempotency-key-missing",
                "Idempotency-Key required",
                ex.getMessage()
        );
    }

    @ExceptionHandler(IdempotencyPayloadMismatchException.class)
    public ProblemDetail handleIdempotencyMismatch(IdempotencyPayloadMismatchException ex) {
        ProblemDetail problem = problem(
                HttpStatus.UNPROCESSABLE_CONTENT,
                "idempotency-key-payload-mismatch",
                "Idempotency-Key payload mismatch",
                ex.getMessage()
        );
        problem.setProperty("idempotencyKey", ex.getIdempotencyKey());
        return problem;
    }

    @ExceptionHandler(IdempotencyKeyInFlightException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyInFlight(IdempotencyKeyInFlightException ex) {
        ProblemDetail problem = problem(
                HttpStatus.CONFLICT,
                "idempotency-key-in-flight",
                "Idempotency-Key request in flight",
                ex.getMessage()
        );
        problem.setProperty("idempotencyKey", ex.getIdempotencyKey());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .header(HttpHeaders.RETRY_AFTER, "1")
                .body(problem);
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
