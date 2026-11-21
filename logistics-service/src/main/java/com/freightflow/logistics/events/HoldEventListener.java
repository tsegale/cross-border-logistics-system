package com.freightflow.logistics.events;

import com.freightflow.logistics.payment.BankingHoldEvents;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * logistics' consumer of banking's hold events (ADR 0028), group {@code logistics-service}, the only group the ACLs
 * let it read with (ADR 0022). A failure is retried in place with backoff, so one hold's events keep their order; a
 * poison message or one that keeps failing goes to {@link EventTopics#HOLD_DEAD_LETTERS} ({@link ConsumerErrors}).
 */
@Component
public class HoldEventListener {

    private final BankingHoldEvents handler;

    public HoldEventListener(BankingHoldEvents handler) {
        this.handler = handler;
    }

    @KafkaListener(id = "logistics-hold-events", topics = EventTopics.BANKING_HOLD, groupId = EventTopics.GROUP,
            autoStartup = "${freightflow.events.consumer.enabled:true}")
    public void onHoldEvent(ConsumerRecord<String, String> record) {
        handler.handle(HoldEvent.parse(record.value()), record.topic());
    }
}
