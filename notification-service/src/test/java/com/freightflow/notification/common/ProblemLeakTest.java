package com.freightflow.notification.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What an exception carries (SQL, a constraint, a class or a setting) stays in the log: the problem a client gets
 * names none of it (ADR 0042, OWASP API8:2023). Only a message written for the caller is passed on.
 */
class ProblemLeakTest {

    private static final String INTERNALS = "duplicate key value violates unique constraint \"accounts_pkey\" "
            + "on table ledger.accounts, at com.freightflow.notification.Secret, password=hunter2";

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    private static void assertNothingLeaks(ProblemDetail problem) {
        String shown = problem.getTitle() + " " + problem.getDetail() + " " + problem.getProperties();
        assertThat(shown).doesNotContain("accounts_pkey", "ledger.accounts", "com.freightflow", "hunter2", "SQL");
    }

    @Test
    void anUnexpectedExceptionIsA500ThatSaysNothingOfIt() {
        var response = handler.handleUnexpected(new IllegalStateException(INTERNALS));
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        assertNothingLeaks(response.getBody());
    }

    @Test
    void aLibrarysIllegalArgumentIsAGeneric400() {
        ProblemDetail problem = handler.handleIllegalArgument(new IllegalArgumentException(INTERNALS));
        assertThat(problem.getStatus()).isEqualTo(400);
        assertNothingLeaks(problem);
    }

    @Test
    void aMessageWrittenForTheCallerIsPassedOn() {
        ProblemDetail problem = handler.handleInvalidRequest(new InvalidRequestException("limit must be between 1 and 200"));
        assertThat(problem.getDetail()).isEqualTo("limit must be between 1 and 200");
    }
}
