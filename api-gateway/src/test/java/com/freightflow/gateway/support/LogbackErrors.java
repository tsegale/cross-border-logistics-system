package com.freightflow.gateway.support;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.status.Status;
import ch.qos.logback.core.status.StatusListener;
import ch.qos.logback.core.status.StatusManager;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Fails a test when logback reported an error since the last check (an appender or encoder that threw, a configuration
 * that did not load). Logback drops the event and carries on, so such a bug fails nothing by itself: a duplicated
 * requestId made the ECS encoder throw on every security event of three services, and no test noticed. Registered for
 * every test through META-INF/services and the surefire setting junit.jupiter.extensions.autodetection.enabled.
 *
 * <p>A status listener, not a look at logback's status list: that list keeps the first 150 and the last 150 statuses,
 * so once a test run has produced 300 its size stops growing and new errors cannot be found by position. The listener
 * sees every status, and is reset resistant, so it stays registered when Spring Boot reconfigures logback for a new
 * application context. Errors from a context starting before a test, or from before the listener was installed, are
 * reported by the next test that runs.
 */
public class LogbackErrors implements BeforeEachCallback, AfterEachCallback {

    private static final Collector COLLECTOR = new Collector();

    @Override
    public void beforeEach(ExtensionContext context) {
        install();
    }

    @Override
    public void afterEach(ExtensionContext context) {
        install();
        List<String> errors = COLLECTOR.drain();
        if (!errors.isEmpty()) {
            throw new AssertionError("logback reported errors (log lines were dropped): " + errors);
        }
    }

    /** The listener every test shares. */
    static StatusListener listener() {
        return COLLECTOR;
    }

    private static synchronized void install() {
        if (!(LoggerFactory.getILoggerFactory() instanceof LoggerContext logback)) {
            return;
        }
        StatusManager manager = logback.getStatusManager();
        if (manager.getCopyOfStatusListenerList().contains(COLLECTOR)) {
            return;
        }
        manager.getCopyOfStatusList().forEach(COLLECTOR::addStatusEvent);
        manager.add(COLLECTOR);
    }

    private static final class Collector implements StatusListener {

        private final Queue<String> errors = new ConcurrentLinkedQueue<>();

        @Override
        public void addStatusEvent(Status status) {
            if (status.getEffectiveLevel() >= Status.ERROR) {
                errors.add(status.getMessage() + (status.getThrowable() == null ? "" : ": " + status.getThrowable()));
            }
        }

        @Override
        public boolean isResetResistant() {
            return true;
        }

        List<String> drain() {
            List<String> drained = new ArrayList<>();
            for (String error = errors.poll(); error != null; error = errors.poll()) {
                drained.add(error);
            }
            return drained;
        }
    }
}
