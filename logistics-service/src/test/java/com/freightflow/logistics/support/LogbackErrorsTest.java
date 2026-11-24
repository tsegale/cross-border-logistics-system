package com.freightflow.logistics.support;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.BasicStatusManager;
import ch.qos.logback.core.status.ErrorStatus;
import ch.qos.logback.core.status.InfoStatus;
import ch.qos.logback.core.status.StatusManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The check itself: an error logback reports fails the test. Each test drains the error it put there on purpose
 * through the extension, so the extension around the test finds nothing left to report.
 */
class LogbackErrorsTest {

    private final StatusManager statuses = ((LoggerContext) LoggerFactory.getILoggerFactory()).getStatusManager();
    private final ExtensionContext context = Mockito.mock(ExtensionContext.class);

    /**
     * Spring Boot reads logback's status list when it configures logging for a new application context, and refuses
     * to start on an error there: the next test's context would fail on the error put there on purpose.
     */
    @AfterEach
    void forgetTheStatusesPutThereOnPurpose() {
        statuses.clear();
    }

    @Test
    void anAppenderErrorFailsTheTest() {
        statuses.add(new ErrorStatus("Duplicate nested pairs", this));

        assertThatThrownBy(() -> new LogbackErrors().afterEach(context))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Duplicate nested pairs");
    }

    @Test
    void anErrorFailsTheTestAfterLogbacksStatusListIsFull() {
        // Logback keeps 300 statuses; past that, the list's size stops growing.
        for (int i = 0; i <= BasicStatusManager.MAX_HEADER_COUNT + BasicStatusManager.TAIL_SIZE; i++) {
            statuses.add(new InfoStatus("filler " + i, this));
        }
        statuses.add(new ErrorStatus("Duplicate nested pairs", this));

        assertThatThrownBy(() -> new LogbackErrors().afterEach(context))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("Duplicate nested pairs");
    }

    @Test
    void theListenerStaysRegisteredWhenLogbackIsReconfigured() {
        assertThat(statuses.getCopyOfStatusListenerList()).contains(LogbackErrors.listener());
        assertThat(LogbackErrors.listener().isResetResistant()).isTrue();
    }
}
