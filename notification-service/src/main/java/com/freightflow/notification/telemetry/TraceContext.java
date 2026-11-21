package com.freightflow.notification.telemetry;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Carries a trace across a table (ADR 0030): a row stores the W3C {@code traceparent} of the transaction that wrote
 * it, and whatever sends the row later, on another thread or instance, does so in a span continuing that trace. Over
 * HTTP and Kafka the same header travels on its own (Spring's client and Kafka observations); a table needs this.
 */
@Component
public class TraceContext {

    public static final String HEADER = "traceparent";
    private static final Pattern VALID = Pattern.compile("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}");

    private final Tracer tracer;
    private final Propagator propagator;

    public TraceContext(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The current span's traceparent, or null outside a sampled span. */
    public String current() {
        Span span = tracer.currentSpan();
        if (span == null || !Boolean.TRUE.equals(span.context().sampled())) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        String traceparent = carrier.get(HEADER);
        return traceparent != null && VALID.matcher(traceparent).matches() ? traceparent : null;
    }

    /**
     * A started span, a child of the stored traceparent: the row's trace goes on. A row without one (written before
     * tracing, or by a job outside any trace) starts a new trace. The caller ends the span.
     */
    public Span continueFrom(String traceparent, String name) {
        Span span = traceparent != null && VALID.matcher(traceparent).matches()
                ? propagator.extract(Map.of(HEADER, traceparent), Map::get).name(name).start()
                : tracer.nextSpan().name(name).start();
        return span;
    }

    public Tracer.SpanInScope inScope(Span span) {
        return tracer.withSpan(span);
    }

    /** Records a moment on the current span, if there is one: an event written, an email queued. */
    public void event(String name) {
        Span span = tracer.currentSpan();
        if (span != null) {
            span.event(name);
        }
    }

    /** Adds a domain attribute (an id, never personal data, ADR 0024) to the current span, if there is one. */
    public void tag(String key, Object value) {
        Span span = tracer.currentSpan();
        if (span != null && value != null) {
            span.tag(key, value.toString());
        }
    }
}
