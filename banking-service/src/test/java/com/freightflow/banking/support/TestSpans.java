package com.freightflow.banking.support;

import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.function.Predicate;

/**
 * Every span the context ends, kept in memory beside the (disabled) OTLP exporter, so tests can read the traces they
 * cause (ADR 0030). The batch processor exports every 50 ms in tests (config/application.yml).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestSpans {

    public static final InMemorySpanExporter EXPORTER = InMemorySpanExporter.create();

    @Bean
    InMemorySpanExporter inMemorySpanExporter() {
        return EXPORTER;
    }

    /** The finished spans of one trace, waiting until {@code done} holds for them or 15 s have passed. */
    public static List<SpanData> trace(String traceId, Predicate<List<SpanData>> done) {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        List<SpanData> spans = List.of();
        while (System.nanoTime() < deadline) {
            spans = EXPORTER.getFinishedSpanItems().stream().filter(s -> s.getTraceId().equals(traceId)).toList();
            if (done.test(spans)) {
                return spans;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return spans;
    }

    /** Every span name in the trace. */
    public static List<String> names(List<SpanData> spans) {
        return spans.stream().map(SpanData::getName).toList();
    }
}
