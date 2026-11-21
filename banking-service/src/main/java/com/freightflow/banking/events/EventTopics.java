package com.freightflow.banking.events;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import java.time.Duration;

/**
 * The topics banking owns and publishes to (ADR 0022). Spring Boot's {@code KafkaAdmin} creates them at startup and
 * brings their configuration in line; the broker never creates a topic on its own ({@code auto.create.topics.enable
 * =false}), and the ACLs let only banking create or write these.
 *
 * <p>Every topic is {@code freightflow.<aggregate>.v<n>}, keyed by the aggregate's id, so one aggregate's events stay
 * in order on one partition. The choices, per topic:
 * <ul>
 *   <li><b>Partitions</b> cap how many instances of one consumer group can share the work. Six where events follow
 *       money movements and holds (the busiest streams; measured peaks are tens per second, far below one
 *       partition's capacity), three where they follow rare actions. Raising the count later reshuffles keys, so
 *       it is set with headroom now.</li>
 *   <li><b>Retention</b> 30 days: a consumer that was down, or a new one, can catch up from the log for a month,
 *       longer than any hold lives. Kafka is not the system of record; the ledger is, so nothing needs to live
 *       longer here.</li>
 *   <li><b>Cleanup</b> {@code delete}, not {@code compact}: these are facts that happened, not the latest state of a
 *       key, and a compacted topic would drop all but the last event of each aggregate.</li>
 *   <li><b>Replication</b> from {@code freightflow.kafka.replication-factor}: 1 on the single local broker, 3 in a
 *       cluster, where {@code min.insync.replicas} 2 with {@code acks=all} survives one broker loss without losing
 *       an acknowledged event.</li>
 * </ul>
 */
@Configuration
public class EventTopics {

    public static final String ACCOUNT = "freightflow.account.v1";
    public static final String JOURNAL_ENTRY = "freightflow.journal-entry.v1";
    public static final String HOLD = "freightflow.hold.v1";
    public static final String APPROVAL = "freightflow.approval.v1";

    static final Duration RETENTION = Duration.ofDays(30);

    private final int replicationFactor;

    public EventTopics(@Value("${freightflow.kafka.replication-factor}") int replicationFactor) {
        this.replicationFactor = replicationFactor;
    }

    /** {@code account.opened}: one per account, rare. */
    @Bean
    NewTopic accountTopic() {
        return topic(ACCOUNT, 3);
    }

    /** {@code journal-entry.posted}: every money movement, the busiest stream. */
    @Bean
    NewTopic journalEntryTopic() {
        return topic(JOURNAL_ENTRY, 6);
    }

    /** {@code hold.authorised|extended|captured|released|expired}: one or more per shipment. */
    @Bean
    NewTopic holdTopic() {
        return topic(HOLD, 6);
    }

    /** {@code approval.decided}: operators' four-eyes decisions, rare. */
    @Bean
    NewTopic approvalTopic() {
        return topic(APPROVAL, 3);
    }

    private NewTopic topic(String name, int partitions) {
        return TopicBuilder.name(name)
                .partitions(partitions)
                .replicas(replicationFactor)
                .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
                .config(TopicConfig.RETENTION_MS_CONFIG, Long.toString(RETENTION.toMillis()))
                .config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, Integer.toString(replicationFactor >= 3 ? 2 : 1))
                .build();
    }
}
