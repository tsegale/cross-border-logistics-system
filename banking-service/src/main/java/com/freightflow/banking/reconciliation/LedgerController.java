package com.freightflow.banking.reconciliation;

import com.freightflow.banking.reconciliation.dto.ReconciliationReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/ledger")
@Tag(name = "Ledger Reconciliation", description = "Double-entry trial balance and account balance reconciliation")
public class LedgerController {

    private final ReconciliationService reconciliationService;

    public LedgerController(ReconciliationService reconciliationService) {
        this.reconciliationService = reconciliationService;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Reconcile materialised account balances against the append-only postings ledger")
    public ReconciliationReportResponse reconcile() {
        return reconciliationService.reconcile();
    }
}
