package com.freightflow.banking.reconciliation;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.approval.ApprovalKind;
import com.freightflow.banking.approval.ApprovalService;
import com.freightflow.banking.approval.dto.ApprovalResponse;
import com.freightflow.banking.approval.dto.CreateApprovalRequest;
import com.freightflow.banking.support.Funding;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.support.TestCallers;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * banking's business metrics (ADR 0031): money moved per currency, approvals waiting, and the reconciliation's drift,
 * which must be zero (docs/slo.md).
 */
class BankingMetricsIntegrationTest extends IntegrationTest {

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private AccountService accounts;

    @Autowired
    private Funding funding;

    @Autowired
    private ApprovalService approvals;

    @Autowired
    private ReconciliationMonitor monitor;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aDepositCountsAsMoneyMovedInItsCurrencyOnceCommitted() {
        AccountResponse account = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("ZAR", TestCallers.NAMIB));
        double before = moved("ZAR", "deposit");

        funding.deposit(account.id().toString(), new BigDecimal("250.00"), "ZAR", "METRICS");

        assertThat(moved("ZAR", "deposit") - before).isCloseTo(250.0, within(0.0001));
    }

    @Test
    void waitingApprovalsTheOldestAndThoseAboutToExpireAreGauged() {
        AccountResponse account = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("EUR", TestCallers.NAMIB));
        double pending = gauge("freightflow.approvals.pending");
        double expiring = gauge("freightflow.approvals.expiring");

        ApprovalResponse requested = approvals.request(TestCallers.OPERATOR, new CreateApprovalRequest(
                ApprovalKind.DEPOSIT, account.id().toString(), new BigDecimal("10.00"), "EUR", "WAIT", null, null, null),
                "metrics-" + UUID.randomUUID());

        assertThat(gauge("freightflow.approvals.pending")).isEqualTo(pending + 1);
        assertThat(gauge("freightflow.approvals.pending.oldest.age")).isGreaterThanOrEqualTo(0);
        assertThat(gauge("freightflow.approvals.expiring")).as("a new request has its full day").isEqualTo(expiring);

        jdbcTemplate.update("UPDATE approval_requests SET expires_at = now() + interval '10 minutes' WHERE id = ?",
                requested.id());
        assertThat(gauge("freightflow.approvals.expiring")).isEqualTo(expiring + 1);
    }

    @Test
    void driftIsPublishedAsSoonAsTheCheckFindsItAndClearsWhenRepaired() {
        AccountResponse account = accounts.createCustomerAccount(TestCallers.OPERATOR,
                new CreateAccountRequest("EUR", TestCallers.NAMIB));
        funding.deposit(account.id().toString(), new BigDecimal("100.00"), "EUR", "DRIFT");
        monitor.check();
        double baseline = gauge("freightflow.ledger.discrepancies");

        jdbcTemplate.update("UPDATE accounts SET balance = balance + 1.0000 WHERE id = ?", account.id());
        try {
            assertThat(monitor.check().balanced()).isFalse();
            assertThat(gauge("freightflow.ledger.discrepancies")).isEqualTo(baseline + 1);
            assertThat(gauge("freightflow.ledger.reconciliation.age")).isBetween(0.0, 5.0);
        } finally {
            jdbcTemplate.update("UPDATE accounts SET balance = balance - 1.0000 WHERE id = ?", account.id());
        }
        monitor.check();
        assertThat(gauge("freightflow.ledger.discrepancies")).isEqualTo(baseline);
    }

    private double moved(String currency, String entryType) {
        var counter = meters.find("freightflow.money.moved").tag("currency", currency).tag("entry_type", entryType)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }
}
