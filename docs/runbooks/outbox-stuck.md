# Outbox stuck

Alerts: OutboxStuck

**What it means.** A service's outbox has a row older than a minute that its relay has not delivered (ADR 0025). Events
or payment commands are waiting; customers' emails, and logistics' reaction to holds, wait with them.

**Check.**
1. [Booking to email](http://127.0.0.1:3000/d/booking-to-email), "Outbox backlog and oldest row": which service and which outbox
   (`payment-commands` or the event outbox).
2. Event outbox: Kafka reachable? `{service_name="<service>"} |= "not acknowledged"`; [Kafka](http://127.0.0.1:3000/d/kafka), "Send
   failures". A broker outage resolves itself within seconds of the broker's return (measured: under 13 s).
3. Payment commands: banking reachable? [RED: banking-service](http://127.0.0.1:3000/d/red-banking-service); the payment relay's spans
   (`payment-command send`) carry their outcome.

**Fix.** Restore the broker or banking; the relays retry with backoff and keep each aggregate's order. A row that fails
for a reason of its own (one event the broker refuses) has `last_error` in the table. **Escalate** if the backlog grows
while Kafka and banking are healthy.
