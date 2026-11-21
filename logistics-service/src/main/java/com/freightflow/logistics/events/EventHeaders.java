package com.freightflow.logistics.events;

/**
 * Kafka record headers on every event (ADR 0023): the envelope's routing fields, readable without parsing the body.
 * The record key is the aggregate id.
 */
public final class EventHeaders {

    public static final String EVENT_ID = "event-id";
    public static final String EVENT_TYPE = "event-type";
    public static final String SCHEMA_VERSION = "schema-version";
    public static final String AGGREGATE_ID = "aggregate-id";
    public static final String SEQUENCE = "sequence";

    private EventHeaders() {
    }
}
