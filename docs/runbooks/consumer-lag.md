# Consumer lag

Alerts: ConsumerLagHigh

**What it means.** A consumer group is more than 100 records behind on a topic for 5 minutes (the collector's
`kafka_metrics` receiver). Emails or logistics' reactions to holds are late.

**Check.**
1. [Kafka](http://127.0.0.1:3000/d/kafka): one partition (a record that keeps failing) or all (the consumer is down or slow).
2. Is the consumer up? `up{job="services"}`, [service-down](service-down.md).
3. `{service_name="notification-service"} |= "retry"`: a record retried on the retry topics.

**Fix.** A down consumer: restart it; it continues from its committed offsets and drops duplicates (ADR 0026). A slow
one: its database or the mail server, see the other runbooks. **Escalate** if the lag keeps growing with the consumer
healthy.
