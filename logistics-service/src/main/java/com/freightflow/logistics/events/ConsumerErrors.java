package com.freightflow.logistics.events;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Failures in logistics' consumers (ADR 0026). Spring Boot puts this error handler on the listener containers.
 *
 * <ul>
 *   <li><b>Retried in place</b>, 200 ms doubling to 5 s, for about 20 s: the partition waits, so a hold's later events
 *       never overtake an earlier one. logistics reads few events and acts on two types, so blocking is cheaper than
 *       the reordering that retry topics bring (notification-service makes the other trade).</li>
 *   <li><b>Then the dead-letter topic</b> {@code <topic>.logistics-service.dlt}, with the original record, its
 *       headers and the exception in {@code kafka_dlt-*} headers, so it can be inspected and replayed by hand.</li>
 *   <li><b>A poison message</b> ({@link PoisonEventException}) skips the retries.</li>
 * </ul>
 */
@Configuration
public class ConsumerErrors {

    private static final Logger log = LoggerFactory.getLogger(ConsumerErrors.class);

    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafka, MeterRegistry meters) {
        DeadLetterPublishingRecoverer deadLetters = new DeadLetterPublishingRecoverer(kafka,
                (record, error) -> new TopicPartition(record.topic() + EventTopics.DEAD_LETTER_SUFFIX, -1));
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2.0);
        backOff.setMaxInterval(5_000);
        backOff.setMaxElapsedTime(20_000);
        DefaultErrorHandler handler = new DefaultErrorHandler((record, error) -> {
            meters.counter("freightflow.events.dead-lettered", "topic", record.topic()).increment();
            log.error("Event at {}-{}@{} goes to the dead-letter topic: {}", record.topic(), record.partition(),
                    record.offset(), error.toString());
            deadLetters.accept(record, error);
        }, backOff);
        handler.addNotRetryableExceptions(PoisonEventException.class);
        return handler;
    }
}
