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

    private final ReconciliationMonitor monitor;

    public LedgerController(ReconciliationMonitor monitor) {
        this.monitor = monitor;
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Reconcile materialised account balances against the append-only postings ledger")
    public ReconciliationReportResponse reconcile() {
        // Through the monitor, so a reconciliation an operator asks for updates the drift gauges too (ADR 0031).
        return monitor.check();
    }
}
