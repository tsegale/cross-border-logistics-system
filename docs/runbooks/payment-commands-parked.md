# Payment commands parked

Alerts: PaymentCommandsParked

**What it means.** A payment command (authorise, extend, capture, release, reauthorise) ran out of attempts against
banking (ADR 0012). Its shipment waits for an operator: a booking stays `AWAITING_PAYMENT`, or a hold is not captured.

**Check.**
1. `GET /api/v1/payment-commands?status=PARKED` as an operator: each command, its last error and problem type.
2. Its trace: search Tempo for `{ span.freightflow.payment_command.id = "<id>" }`.
3. [Booking to email](http://127.0.0.1:3000/d/booking-to-email), "Payment commands by outcome": one command or many.

**Fix.** Many at once: banking was down or refusing; once it answers, retry each with
`POST /api/v1/payment-commands/{id}/retry` (same idempotency key, so banking never applies one twice, ADR 0006). One:
read its problem (insufficient funds, an expired hold) and resolve it with the customer before retrying.
**Escalate** to the logistics owner if a retry parks again with the same error.
