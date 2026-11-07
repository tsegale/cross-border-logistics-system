package com.freightflow.banking.reconciliation;

import com.freightflow.banking.account.AccountService;
import com.freightflow.banking.account.dto.AccountResponse;
import com.freightflow.banking.account.dto.CreateAccountRequest;
import com.freightflow.banking.journal.LedgerService;
import com.freightflow.banking.journal.dto.DepositRequest;
import com.freightflow.banking.support.IntegrationTest;
import com.freightflow.banking.transfer.TransferService;
import com.freightflow.banking.transfer.dto.CreateTransferRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReconciliationIntegrationTest extends IntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AccountService accountService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private TransferService transferService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reconciliationReportsBalancedLedgerAndDetectsTamperedMaterialisedBalance() throws Exception {
        AccountResponse a = accountService.createCustomerAccount(new CreateAccountRequest("Recon A", "NAD"));
        AccountResponse b = accountService.createCustomerAccount(new CreateAccountRequest("Recon B", "NAD"));

        ledgerService.deposit(
                a.id().toString(),
                new DepositRequest(new BigDecimal("1500.00"), "NAD", "Recon deposit"),
                "recon-dep-" + UUID.randomUUID()
        );
        transferService.createTransfer(
                new CreateTransferRequest(a.accountNumber(), b.accountNumber(), new BigDecimal("350.25"), "NAD", "Recon transfer", null),
                "recon-tx-" + UUID.randomUUID()
        );

        mockMvc.perform(get("/api/v1/ledger/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanced").value(true))
                .andExpect(jsonPath("$.discrepancies").isEmpty())
                .andExpect(jsonPath("$.trialBalances.length()").value(3))
                .andExpect(jsonPath("$.trialBalances[0].balanced").value(true))
                .andExpect(jsonPath("$.trialBalances[1].balanced").value(true))
                .andExpect(jsonPath("$.trialBalances[2].balanced").value(true));

        // Simulate a direct SQL corruption of accounts.balance and verify reconciliation catches it
        jdbcTemplate.update("UPDATE accounts SET balance = balance + 42.0000 WHERE id = ?", a.id());
        try {
            mockMvc.perform(get("/api/v1/ledger/reconciliation"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.balanced").value(false))
                    .andExpect(jsonPath("$.discrepancies.length()").value(1))
                    .andExpect(jsonPath("$.discrepancies[0].accountNumber").value(a.accountNumber()))
                    .andExpect(jsonPath("$.discrepancies[0].difference").value(42.00));
        } finally {
            jdbcTemplate.update("UPDATE accounts SET balance = balance - 42.0000 WHERE id = ?", a.id());
        }
    }
}
