package com.freightflow.notification.common;

import org.springframework.boot.jackson.autoconfigure.JsonFactoryBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;

/**
 * Bounds on every JSON document this service reads (ADR 0042), far below Jackson 3's own defaults and well above any
 * real request: an attacker's deeply nested or enormous document is refused while it is parsed, a 400 problem,
 * instead of costing stack or memory. The gateway has already refused any body over 1 MB.
 */
@Configuration(proxyBeanMethods = false)
public class JsonLimits {

    /** The constraints, for Boot's mapper and for any mapper the service builds itself. */
    public static final StreamReadConstraints CONSTRAINTS = StreamReadConstraints.builder()
            // Requests nest a few levels (a shipment, its parties, their addresses); DCSA events a few more.
            .maxNestingDepth(32)
            .maxDocumentLength(1_048_576)
            .maxStringLength(100_000)
            .maxNameLength(256)
            // A NUMERIC(19,4) amount is at most 24 characters: no number needs more.
            .maxNumberLength(64)
            .maxTokenCount(100_000)
            .build();

    @Bean
    JsonFactoryBuilderCustomizer boundedJson() {
        return builder -> builder.streamReadConstraints(CONSTRAINTS);
    }
}
