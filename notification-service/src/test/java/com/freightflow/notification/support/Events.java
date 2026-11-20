package com.freightflow.notification.support;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Test events built from the schemas' own examples (docs/events/), changed where a test needs it and checked against
 * the schema again, then published as their real producer (logistics or banking) to the real topic.
 */
public final class Events {

    private Events() {
    }

    /** The example of {@code type}, edited by {@code edit}, with a fresh UUIDv7 event id. */
    public static String event(String type, Consumer<ObjectNode> edit) {
        ObjectNode event = (ObjectNode) EventSchemas.files().get(type + ".schema.json").path("examples").get(0)
                .deepCopy();
        event.put("eventId", uuidV7().toString());
        edit.accept(event);
        String json = event.toString();
        assertThat(EventSchemas.validate(type, json)).as("test event matches %s", type).isEmpty();
        return json;
    }

    public static ObjectNode payload(ObjectNode event) {
        return (ObjectNode) event.path("payload");
    }

    /** A random UUID with version 7 and the RFC 9562 variant, as the envelope requires. */
    public static UUID uuidV7() {
        UUID random = UUID.randomUUID();
        long msb = (random.getMostSignificantBits() & ~0xF000L) | 0x7000L;
        long lsb = (random.getLeastSignificantBits() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(msb, lsb);
    }

    /** Publishes as {@code user} (logistics writes shipment events, banking hold events: ADR 0022). */
    public static void publish(String user, String topic, String key, String value) {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(SharedContainers.KAFKA.clientConfig(user),
                new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish to " + topic, e);
        }
    }

    /** Polls until the supplier returns non-null, for up to {@code seconds}. */
    public static <T> T await(int seconds, Supplier<T> condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            T value = condition.get();
            if (value != null && !(value instanceof Boolean b && !b)) {
                return value;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition not met within " + seconds + " s");
    }
}
