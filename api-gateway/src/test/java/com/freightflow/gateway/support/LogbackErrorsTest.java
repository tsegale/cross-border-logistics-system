package com.freightflow.gateway.support;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.ErrorStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/** The check itself: an error logback reports during a test fails that test. */
class LogbackErrorsTest {

    @Test
    void anAppenderErrorFailsTheTest() {
        LoggerContext logback = (LoggerContext) LoggerFactory.getILoggerFactory();
        ExtensionContext.Store store = Mockito.mock(ExtensionContext.Store.class);
        ExtensionContext context = Mockito.mock(ExtensionContext.class);
        Mockito.when(context.getStore(any())).thenReturn(store);
        Mockito.when(store.get("seen", Integer.class))
                .thenReturn(logback.getStatusManager().getCopyOfStatusList().size());
        try {
            logback.getStatusManager().add(new ErrorStatus("Duplicate nested pairs", this));
            assertThatThrownBy(() -> new LogbackErrors().afterEach(context))
                    .isInstanceOf(AssertionError.class)
                    .hasMessageContaining("Duplicate nested pairs");
        } finally {
            // So the extension around this test does not report the error put there on purpose.
            logback.getStatusManager().clear();
        }
    }
}
