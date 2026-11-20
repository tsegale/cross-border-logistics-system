package com.freightflow.notification.events;

/**
 * The topics notification-service reads (ADR 0022) and the ones Spring Kafka derives from them for its retries and
 * dead letters (ADR 0026). It owns the derived ones: {@code infra/kafka/acls.conf} grants it the prefix
 * {@code <topic>.notification-service}, and the consumer groups under the prefix {@code notification-service}.
 */
public final class EventTopics {

    public static final String SHIPMENT = "freightflow.shipment.v1";
    public static final String HOLD = "freightflow.hold.v1";
    public static final String GROUP = "notification-service";
    /** {@code freightflow.hold.v1.notification-service-retry-0}, {@code -1}, ...; groups get the same suffix. */
    public static final String RETRY_SUFFIX = ".notification-service-retry";
    /** {@code freightflow.hold.v1.notification-service-dlt}. */
    public static final String DLT_SUFFIX = ".notification-service-dlt";

    private EventTopics() {
    }
}
