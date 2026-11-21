package com.freightflow.notification.telemetry;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.ObservationFilter;
import io.micrometer.observation.ObservationPredicate;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/** OpenTelemetry beyond what Spring Boot configures (ADR 0030). */
@Configuration(proxyBeanMethods = false)
public class TelemetryConfig {

    /**
     * No traces for polling loops, probes, scrapes or Spring Security's filter chain: a relay polling five times a
     * second would bury every real trace. Work a poll finds gets its own span, continuing the trace that wrote the
     * row (ADR 0030).
     */
    @Bean
    ObservationPredicate noTracesForPollingOrProbes() {
        return (name, context) -> {
            if (name.startsWith("tasks.scheduled") || name.startsWith("spring.security.")) {
                return false;
            }
            return !(context instanceof ServerRequestObservationContext request)
                    || !request.getCarrier().getRequestURI().startsWith("/actuator");
        };
    }

    /**
     * The request id (X-Request-Id, ADR 0015) on the server span, so a log line's requestId leads to its trace and a
     * trace to its log lines also by the id a caller was given (ADR 0030). High cardinality: on the span only, never a
     * metric label.
     */
    @Bean
    ObservationFilter requestIdOnServerSpans() {
        return context -> {
            if (context instanceof ServerRequestObservationContext request && request.getResponse() != null) {
                String requestId = request.getResponse().getHeader("X-Request-Id");
                if (requestId != null) {
                    context.addHighCardinalityKeyValue(KeyValue.of("freightflow.request_id", requestId));
                }
            }
            return context;
        };
    }

    /** Spring Boot provides the SDK but not the Logback appender, which has to be told which SDK to use. */
    @Bean
    InitializingBean installLogExport(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
