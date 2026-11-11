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
                             Instant expiresAt) {
    }

    public record CaptureHold(BigDecimal amount) {
    }

    public record ExtendHold(Instant expiresAt) {
    }

    public record Hold(UUID id, String status, BigDecimal amount, String currency, BigDecimal payerAmount,
                       String payerCurrency, BigDecimal fxRate, BigDecimal capturedAmount, Instant expiresAt) {
    }
}
