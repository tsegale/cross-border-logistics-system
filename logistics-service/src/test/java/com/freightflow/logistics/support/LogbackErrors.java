package com.freightflow.logistics.support;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Fails the test during which logback reported an error (an appender or encoder that threw, a configuration that did
 * not load). Logback drops the event and carries on, so such a bug fails nothing by itself: a duplicated requestId
 * made the ECS encoder throw on every security event of three services, and no test noticed. Registered for every
 * test through META-INF/services and the surefire setting junit.jupiter.extensions.autodetection.enabled.
 */
public class LogbackErrors implements BeforeEachCallback, AfterEachCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(LogbackErrors.class);

    @Override
    public void beforeEach(ExtensionContext context) {
        context.getStore(NAMESPACE).put("seen", statuses().size());
    }

    @Override
    public void afterEach(ExtensionContext context) {
        Integer seen = context.getStore(NAMESPACE).get("seen", Integer.class);
        List<Status> all = statuses();
        List<String> errors = all.subList(Math.min(seen == null ? 0 : seen, all.size()), all.size()).stream()
                .filter(status -> status.getEffectiveLevel() >= Status.ERROR)
                .map(status -> status.getMessage() + (status.getThrowable() == null ? ""
                        : ": " + status.getThrowable()))
                .toList();
        if (!errors.isEmpty()) {
            throw new AssertionError("logback reported errors during this test (log lines were dropped): " + errors);
        }
    }

    private static List<Status> statuses() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext logback)) {
            return List.of();
        }
        return logback.getStatusManager().getCopyOfStatusList();
    }
}
