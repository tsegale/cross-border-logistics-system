# Runbooks

One per alert (infra/observability/rules, docs/slo.md). Each says what the alert means, what to check (the
dashboard and the query), and how to fix or escalate. Grafana is on 127.0.0.1:3000 with the observability
profile.

| Runbook | Alerts |
| --- | --- |
| [slo-booking-availability](slo-booking-availability.md) | BookingAvailabilityBudgetBurnFast, BookingAvailabilityBudgetBurnSlow |
| [slo-transfer-availability](slo-transfer-availability.md) | TransferAvailabilityBudgetBurnFast, TransferAvailabilityBudgetBurnSlow |
| [slo-booking-latency](slo-booking-latency.md) | BookingLatencyBudgetBurnFast, BookingLatencyBudgetBurnSlow |
| [slo-email-delivery](slo-email-delivery.md) | EmailDeliveryBudgetBurnFast, EmailDeliveryBudgetBurnSlow |
| [ledger-drift](ledger-drift.md) | LedgerDrift, ReconciliationNotRunning |
| [payment-commands-parked](payment-commands-parked.md) | PaymentCommandsParked |
| [approvals-near-expiry](approvals-near-expiry.md) | ApprovalsNearExpiry |
| [fx-reference-rates](fx-reference-rates.md) | FxReferenceRateStale, FxReferenceRateQuarantined |
| [customs-tariff-review](customs-tariff-review.md) | CustomsTariffReviewOverdue |
| [outbox-stuck](outbox-stuck.md) | OutboxStuck |
| [consumer-lag](consumer-lag.md) | ConsumerLagHigh |
| [dead-letters](dead-letters.md) | DeadLettersWaiting |
| [emails-unknown](emails-unknown.md) | EmailsUnknown |
| [emails-failing](emails-failing.md) | EmailsFailing |
| [service-down](service-down.md) | ServiceDown, GatewayUpstreamErrors |
| [container-memory](container-memory.md) | ContainerMemoryNearLimit |
