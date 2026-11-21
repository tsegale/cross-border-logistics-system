package com.freightflow.banking.support;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.testing.trace.TestSpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The leak check itself: it must find what it is there to find, or the trace tests prove nothing (ADR 0030). */
class TelemetryLeaksTest {

    @Test
    void findsAnEmailAddressOrATokenInSpansAndLogs() {
        assertThat(TelemetryLeaks.in(List.of(span("recipient", "ops@nordsee-coldstore.de")))).hasSize(1);
        assertThat(TelemetryLeaks.in(List.of(span("http.request.header.authorization", "Bearer eyJhbGciOiJSUzI1NiJ9"))))
                .isNotEmpty();
        assertThat(TelemetryLeaks.in("{\"message\":\"sent to shipping@erongomarine.na\"}")).hasSize(1);
        assertThat(TelemetryLeaks.in("Authorization: Bearer abc.def.ghi")).hasSize(1);
    }

    @Test
    void letsIdsAndKafkasAuthorisationWarningsThrough() {
        assertThat(TelemetryLeaks.in(List.of(span("freightflow.shipment.id", "01a1fd23-0489-7dee-b823-d94936eaaafd"))))
                .isEmpty();
        assertThat(TelemetryLeaks.in("Topic authorization failed for topics [freightflow.shipment.v1]")).isEmpty();
    }

    private static TestSpanData span(String key, String value) {
        return TestSpanData.builder()
                .setName("test")
                .setKind(SpanKind.INTERNAL)
                .setStartEpochNanos(1)
                .setEndEpochNanos(2)
                .setHasEnded(true)
                .setStatus(StatusData.unset())
                .setTotalRecordedEvents(0)
                .setTotalRecordedLinks(0)
                .setTotalAttributeCount(1)
                .setAttributes(Attributes.builder().put(key, value).build())
                .build();
    }
}
