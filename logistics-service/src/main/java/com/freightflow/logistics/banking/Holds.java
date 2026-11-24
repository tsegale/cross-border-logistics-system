package com.freightflow.logistics.banking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The hold request and response as logistics uses them: only the fields it sends or reads (ADR 0002). Checked
 * against banking's committed OpenAPI document by {@code BankingContractTest} (ADR 0014).
 */
public final class Holds {

    private Holds() {
    }

    public record CreateHold(String payerAccount, BigDecimal amount, String currency, String reference,
                             Instant expiresAt, UUID payerOrganizationId, String onBehalfOfSubject) {
    }

    /** {@code disbursementAmount}: the part that is duties and taxes for customs (ADR 0036); null for none. */
    public record CaptureHold(BigDecimal amount, BigDecimal disbursementAmount) {
    }

    public record CustomsSettlement(BigDecimal assessedAmount, String authorityReference) {
    }

    public record CustomsSettled(UUID id, UUID holdId, String currency, BigDecimal estimate, BigDecimal assessed,
                                 BigDecimal difference, UUID journalEntryId) {
    }

    public record ExtendHold(Instant expiresAt) {
    }

    public record Hold(UUID id, String status, BigDecimal amount, String currency, BigDecimal payerAmount,
                       String payerCurrency, BigDecimal fxRate, BigDecimal capturedAmount, Instant expiresAt) {
    }
}
